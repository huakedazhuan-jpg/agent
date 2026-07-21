package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunCoordinator;
import com.hkdzagent.agent.runtime.AgentRunStatus;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeishuEventProcessorTest {

    @Test
    void marksSuccessfullyHandledEventAsProcessed() {
        InMemoryFeishuEventInboxRepository repository = new InMemoryFeishuEventInboxRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AgentRunCoordinator runCoordinator = mock(AgentRunCoordinator.class);
        FeishuReplyClient replyClient = mock(FeishuReplyClient.class);
        AgentRun completed = run(AgentRunStatus.COMPLETED, "answer");
        when(runCoordinator.execute(
                eq(ActorIdentity.feishu("open-1")),
                eq("open-1"),
                eq(conversationId("open-1")),
                eq("hello"),
                eq("feishu")))
                .thenReturn(completed);
        repository.receive(event("event-success", clock.instant()));

        processor(repository, clock, runCoordinator, replyClient, Runnable::run, 3)
                .processAsync("event-success");

        FeishuInboxEvent stored = repository.findById("event-success");
        assertThat(stored.status()).isEqualTo(FeishuInboxEvent.Status.PROCESSED);
        assertThat(stored.retryCount()).isOne();
        assertThat(stored.processedAt()).isEqualTo(clock.instant());
        verifyNoInteractions(replyClient);
    }

    @Test
    void retriesTransientFailureThenMovesExhaustedEventToDead() {
        InMemoryFeishuEventInboxRepository repository = new InMemoryFeishuEventInboxRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AgentRunCoordinator runCoordinator = mock(AgentRunCoordinator.class);
        FeishuReplyClient replyClient = mock(FeishuReplyClient.class);
        when(runCoordinator.execute(
                eq(ActorIdentity.feishu("open-1")),
                eq("open-1"),
                eq(conversationId("open-1")),
                eq("hello"),
                eq("feishu")))
                .thenThrow(new IllegalStateException("api_key=secret-value model unavailable"));
        repository.receive(event("event-failure", clock.instant()));
        FeishuEventProcessor processor = processor(
                repository, clock, runCoordinator, replyClient, Runnable::run, 2
        );

        processor.processAsync("event-failure");

        FeishuInboxEvent retryable = repository.findById("event-failure");
        assertThat(retryable.status()).isEqualTo(FeishuInboxEvent.Status.RETRYABLE);
        assertThat(retryable.lastError()).contains("[redacted]").doesNotContain("secret-value");
        verify(replyClient, never()).replyText(anyString(), anyString());

        clock.advance(Duration.ofSeconds(30));
        processor.processAsync("event-failure");

        assertThat(repository.findById("event-failure").status())
                .isEqualTo(FeishuInboxEvent.Status.DEAD);
        verify(runCoordinator, times(2)).execute(
                ActorIdentity.feishu("open-1"), "open-1",
                conversationId("open-1"), "hello", "feishu");
        verify(replyClient).replyText(org.mockito.ArgumentMatchers.eq("open-1"), anyString());
    }

    @Test
    void waitingApprovalIsAlreadyQueuedAndMarksInboxEventProcessedWithoutDirectReply() {
        InMemoryFeishuEventInboxRepository repository = new InMemoryFeishuEventInboxRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AgentRunCoordinator runCoordinator = mock(AgentRunCoordinator.class);
        FeishuReplyClient replyClient = mock(FeishuReplyClient.class);
        AgentRun waiting = run(AgentRunStatus.WAITING_APPROVAL, null);
        when(waiting.runId()).thenReturn("550e8400-e29b-41d4-a716-446655440010");
        when(runCoordinator.execute(
                eq(ActorIdentity.feishu("open-1")),
                eq("open-1"),
                eq(conversationId("open-1")),
                eq("hello"),
                eq("feishu")))
                .thenReturn(waiting);
        repository.receive(event("event-waiting", clock.instant()));

        processor(repository, clock, runCoordinator, replyClient, Runnable::run, 3)
                .processAsync("event-waiting");

        assertThat(repository.findById("event-waiting").status())
                .isEqualTo(FeishuInboxEvent.Status.PROCESSED);
        verifyNoInteractions(replyClient);
    }

    @Test
    void failedRunIsAlreadyQueuedAndMarksInboxEventProcessedWithoutRetryOrDirectReply() {
        InMemoryFeishuEventInboxRepository repository = new InMemoryFeishuEventInboxRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AgentRunCoordinator runCoordinator = mock(AgentRunCoordinator.class);
        FeishuReplyClient replyClient = mock(FeishuReplyClient.class);
        AgentRun failed = run(AgentRunStatus.FAILED, null);
        when(runCoordinator.execute(
                eq(ActorIdentity.feishu("open-1")), eq("open-1"),
                eq(conversationId("open-1")), eq("hello"), eq("feishu")))
                .thenReturn(failed);
        repository.receive(event("event-run-failed", clock.instant()));

        processor(repository, clock, runCoordinator, replyClient, Runnable::run, 3)
                .processAsync("event-run-failed");

        FeishuInboxEvent stored = repository.findById("event-run-failed");
        assertThat(stored.status()).isEqualTo(FeishuInboxEvent.Status.PROCESSED);
        assertThat(stored.retryCount()).isOne();
        verify(runCoordinator, times(1)).execute(
                ActorIdentity.feishu("open-1"), "open-1",
                conversationId("open-1"), "hello", "feishu");
        verifyNoInteractions(replyClient);
    }

    @Test
    void leavesReceivedEventRecoverableWhenExecutorRejectsSubmission() {
        InMemoryFeishuEventInboxRepository repository = new InMemoryFeishuEventInboxRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        repository.receive(event("event-rejected", clock.instant()));
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        FeishuEventProcessor processor = processor(
                repository,
                clock,
                mock(AgentRunCoordinator.class),
                mock(FeishuReplyClient.class),
                rejectingExecutor,
                3
        );

        assertThatCode(() -> processor.processAsync("event-rejected")).doesNotThrowAnyException();
        assertThat(repository.findById("event-rejected").status())
                .isEqualTo(FeishuInboxEvent.Status.RECEIVED);
        assertThat(repository.findReadyEventIds(
                clock.instant(), Duration.ofMinutes(5), 3, 10))
                .containsExactly("event-rejected");
    }

    private FeishuEventProcessor processor(
            FeishuEventInboxRepository repository,
            Clock clock,
            AgentRunCoordinator runCoordinator,
            FeishuReplyClient replyClient,
            Executor executor,
            int maxAttempts
    ) {
        FeishuProperties properties = new FeishuProperties();
        properties.inbox().setMaxAttempts(maxAttempts);
        properties.inbox().setRetryDelay(Duration.ofSeconds(30));
        properties.inbox().setProcessingTimeout(Duration.ofMinutes(5));
        return new FeishuEventProcessor(
                new ObjectMapper(), runCoordinator, replyClient,
                repository, properties, executor, clock
        );
    }

    private AgentRun run(AgentRunStatus status, String answer) {
        AgentRun run = mock(AgentRun.class);
        when(run.status()).thenReturn(status);
        when(run.finalAnswer()).thenReturn(answer);
        return run;
    }

    private FeishuInboxEvent event(String eventId, Instant receivedAt) {
        String payload = """
                {
                  "header": {
                    "event_id": "%s",
                    "event_type": "im.message.receive_v1"
                  },
                  "event": {
                    "sender": {"sender_id": {"open_id": "open-1"}},
                    "message": {"content": "{\\"text\\":\\"hello\\"}"}
                  }
                }
                """.formatted(eventId);
        return new FeishuInboxEvent(
                eventId,
                "im.message.receive_v1",
                "open-1",
                payload,
                FeishuInboxEvent.Status.RECEIVED,
                receivedAt,
                null,
                null,
                receivedAt,
                0,
                null
        );
    }

    private String conversationId(String openId) {
        return new OwnedConversationId(ActorIdentity.feishu(openId), openId).encode();
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
