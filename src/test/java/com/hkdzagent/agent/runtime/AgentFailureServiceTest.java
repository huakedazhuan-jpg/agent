package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.im.FeishuResultOutboxMessage;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.im.InMemoryFeishuResultOutboxRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFailureServiceTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AgentRuntimeService runtime = new AgentRuntimeService(
            new InMemoryAgentRunRepository(), new AgentRuntimeProperties(),
            new ObjectMapper(), clock);
    private final InMemoryFeishuResultOutboxRepository outbox =
            new InMemoryFeishuResultOutboxRepository();
    private final AgentFailureService service = new AgentFailureService(
            runtime, new FeishuResultOutboxService(outbox, clock));

    @Test
    void failsRunningRunAndQueuesNotificationWithEvent() {
        AgentRun created = runtime.create(
                ActorIdentity.feishu("open-1"), "open-1", "conversation-1",
                "trace-failure", "question");
        AgentRunClaim claim = runtime.claim(created.runId(), "worker-1");

        AgentRunEvent event = service.fail(
                created.runId(), claim.run().leaseOwner(), "model unavailable");

        AgentRun failed = runtime.find(created.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(event.type()).isEqualTo(AgentRunEventType.RUN_FAILED);
        assertThat(outbox.findByDeduplicationKey(
                "run:" + created.runId() + ":run-failed").type())
                .isEqualTo(FeishuResultOutboxMessage.Type.RUN_FAILED);
    }

    @Test
    void rejectsWaitingApprovalAndQueuesTheSameFailureNotificationType() {
        AgentRun created = runtime.create(
                ActorIdentity.feishu("open-2"), "open-2", "conversation-2",
                "trace-rejection", "question");
        AgentRunClaim claim = runtime.claim(created.runId(), "worker-2");
        String approvalId = UUID.randomUUID().toString();
        runtime.waitForApproval(
                created.runId(), claim.run().leaseOwner(), approvalId, "{}");

        service.rejectApproval(created.runId(), approvalId, "not allowed");

        AgentRun failed = runtime.find(created.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("not allowed");
        assertThat(outbox.findByDeduplicationKey(
                "run:" + created.runId() + ":run-failed")).isNotNull();
    }
}
