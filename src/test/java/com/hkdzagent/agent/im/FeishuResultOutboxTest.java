package com.hkdzagent.agent.im;

import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuResultOutboxTest {

    @Test
    void completedFeishuRunIsEnqueuedOnceWhileHttpRunIsIgnored() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxService service =
                new FeishuResultOutboxService(repository, clock);
        AgentRun feishuRun = run(
                "550e8400-e29b-41d4-a716-446655440020",
                "feishu:open-1", "final answer");
        AgentRun httpRun = run(
                "550e8400-e29b-41d4-a716-446655440021",
                "user:user-1", "http answer");

        assertThat(service.enqueueCompletedRun(feishuRun)).isTrue();
        assertThat(service.enqueueCompletedRun(feishuRun)).isFalse();
        assertThat(service.enqueueCompletedRun(httpRun)).isFalse();

        FeishuResultOutboxMessage queued = repository.findByDeduplicationKey(
                "run:" + feishuRun.runId() + ":final-result");
        assertThat(queued.status()).isEqualTo(FeishuResultOutboxMessage.Status.PENDING);
        assertThat(queued.openId()).isEqualTo("open-1");
        assertThat(queued.text()).isEqualTo("final answer");
        assertThat(queued.type()).isEqualTo(FeishuResultOutboxMessage.Type.FINAL_RESULT);
        assertThat(repository.findByDeduplicationKey(
                "run:" + httpRun.runId() + ":final-result")).isNull();
    }

    @Test
    void waitingFeishuApprovalIsEnqueuedOnceWhileNonFeishuRunIsIgnored() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxService service =
                new FeishuResultOutboxService(repository, clock);
        String approvalId = "550e8400-e29b-41d4-a716-446655440099";
        AgentRun feishuRun = waitingRun(
                "550e8400-e29b-41d4-a716-446655440020",
                "feishu:open-1", approvalId);
        AgentRun httpRun = waitingRun(
                "550e8400-e29b-41d4-a716-446655440021",
                "user:user-1", approvalId);

        assertThat(service.enqueueApprovalRequired(feishuRun)).isTrue();
        assertThat(service.enqueueApprovalRequired(feishuRun)).isFalse();
        assertThat(service.enqueueApprovalRequired(httpRun)).isFalse();

        String deduplicationKey = "run:" + feishuRun.runId()
                + ":approval:" + approvalId;
        FeishuResultOutboxMessage queued =
                repository.findByDeduplicationKey(deduplicationKey);
        assertThat(queued.type())
                .isEqualTo(FeishuResultOutboxMessage.Type.APPROVAL_REQUIRED);
        assertThat(queued.openId()).isEqualTo("open-1");
        assertThat(queued.text()).isEqualTo(
                "Tool approval required. runId=" + feishuRun.runId());
    }

    @Test
    void failedFeishuRunQueuesOneSafeNotificationWithoutInternalError() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxService service =
                new FeishuResultOutboxService(repository, clock);
        AgentRun failed = mock(AgentRun.class);
        when(failed.runId()).thenReturn("550e8400-e29b-41d4-a716-446655440020");
        when(failed.ownerKey()).thenReturn("feishu:open-1");
        when(failed.status()).thenReturn(AgentRunStatus.FAILED);
        when(failed.errorMessage()).thenReturn("api_key=secret-value unavailable");

        assertThat(service.enqueueFailedRun(failed)).isTrue();
        assertThat(service.enqueueFailedRun(failed)).isFalse();

        FeishuResultOutboxMessage queued = repository.findByDeduplicationKey(
                "run:" + failed.runId() + ":run-failed");
        assertThat(queued.type()).isEqualTo(FeishuResultOutboxMessage.Type.RUN_FAILED);
        assertThat(queued.text())
                .contains(failed.runId())
                .doesNotContain("secret-value", "api_key");
    }

    @Test
    void deliveryFailureRetriesAfterDelayThenMarksMessageSent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuReplyClient replyClient = mock(FeishuReplyClient.class);
        doThrow(new IllegalStateException("temporary failure"))
                .doNothing()
                .when(replyClient).replyText("open-1", "final answer");
        FeishuProperties properties = properties(3);
        FeishuResultOutboxProcessor processor = new FeishuResultOutboxProcessor(
                repository, replyClient, properties, clock);
        FeishuResultOutboxMessage message = message();
        repository.enqueue(message);

        processor.process(message.id());

        FeishuResultOutboxMessage retryable = repository.findById(message.id());
        assertThat(retryable.status())
                .isEqualTo(FeishuResultOutboxMessage.Status.RETRYABLE);
        assertThat(retryable.attemptCount()).isOne();
        assertThat(retryable.nextAttemptAt())
                .isEqualTo(clock.instant().plusSeconds(30));

        processor.process(message.id());
        verify(replyClient, times(1)).replyText("open-1", "final answer");

        clock.advance(Duration.ofSeconds(30));
        processor.process(message.id());

        FeishuResultOutboxMessage sent = repository.findById(message.id());
        assertThat(sent.status()).isEqualTo(FeishuResultOutboxMessage.Status.SENT);
        assertThat(sent.attemptCount()).isEqualTo(2);
        assertThat(sent.sentAt()).isEqualTo(clock.instant());
        assertThat(sent.lastError()).isNull();
        verify(replyClient, times(2)).replyText("open-1", "final answer");

        processor.process(message.id());
        verify(replyClient, times(2)).replyText("open-1", "final answer");
    }

    @Test
    void finalFailureMovesMessageToDeadAndSanitizesError() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuReplyClient replyClient = mock(FeishuReplyClient.class);
        doThrow(new IllegalStateException("api_key=secret-value unavailable"))
                .when(replyClient).replyText("open-1", "final answer");
        FeishuResultOutboxProcessor processor = new FeishuResultOutboxProcessor(
                repository, replyClient, properties(1), clock);
        FeishuResultOutboxMessage message = message();
        repository.enqueue(message);

        processor.process(message.id());

        FeishuResultOutboxMessage dead = repository.findById(message.id());
        assertThat(dead.status()).isEqualTo(FeishuResultOutboxMessage.Status.DEAD);
        assertThat(dead.deadAt()).isEqualTo(clock.instant());
        assertThat(dead.sentAt()).isNull();
        assertThat(dead.lastError()).contains("[redacted]").doesNotContain("secret-value");
    }

    @Test
    void schedulerSubmitsOnlyReadyMessages() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxProcessor processor = mock(FeishuResultOutboxProcessor.class);
        FeishuProperties properties = properties(3);
        FeishuResultOutboxMessage message = message();
        repository.enqueue(message);
        FeishuResultOutboxScheduler scheduler = new FeishuResultOutboxScheduler(
                repository, processor, properties, clock);

        scheduler.deliverReadyMessages();

        verify(processor).process(message.id());
    }

    @Test
    void schedulerDeadLettersAnExpiredFinalClaim() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxProcessor processor = mock(FeishuResultOutboxProcessor.class);
        FeishuProperties properties = properties(1);
        FeishuResultOutboxMessage message = message();
        repository.enqueue(message);
        repository.claim(message.id(), clock.instant(), Duration.ofMinutes(5), 1);
        clock.advance(Duration.ofMinutes(5));
        FeishuResultOutboxScheduler scheduler = new FeishuResultOutboxScheduler(
                repository, processor, properties, clock);

        scheduler.deliverReadyMessages();

        FeishuResultOutboxMessage dead = repository.findById(message.id());
        assertThat(dead.status()).isEqualTo(FeishuResultOutboxMessage.Status.DEAD);
        assertThat(dead.deadAt()).isEqualTo(clock.instant());
        assertThat(dead.lastError()).contains("final attempt");
    }

    private AgentRun run(String runId, String ownerKey, String answer) {
        AgentRun run = mock(AgentRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.ownerKey()).thenReturn(ownerKey);
        when(run.status()).thenReturn(AgentRunStatus.COMPLETED);
        when(run.finalAnswer()).thenReturn(answer);
        return run;
    }

    private AgentRun waitingRun(String runId, String ownerKey, String approvalId) {
        AgentRun run = mock(AgentRun.class);
        when(run.runId()).thenReturn(runId);
        when(run.ownerKey()).thenReturn(ownerKey);
        when(run.status()).thenReturn(AgentRunStatus.WAITING_APPROVAL);
        when(run.pendingApprovalId()).thenReturn(approvalId);
        return run;
    }

    private FeishuResultOutboxMessage message() {
        return new FeishuResultOutboxMessage(
                "550e8400-e29b-41d4-a716-446655440030",
                "550e8400-e29b-41d4-a716-446655440020",
                "open-1", "final answer",
                FeishuResultOutboxMessage.Type.FINAL_RESULT,
                "run:550e8400-e29b-41d4-a716-446655440020:final-result",
                FeishuResultOutboxMessage.Status.PENDING,
                Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, 0, null);
    }

    private FeishuProperties properties(int maxAttempts) {
        FeishuProperties properties = new FeishuProperties();
        properties.outbox().setMaxAttempts(maxAttempts);
        properties.outbox().setRetryDelay(Duration.ofSeconds(30));
        properties.outbox().setProcessingTimeout(Duration.ofMinutes(5));
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
