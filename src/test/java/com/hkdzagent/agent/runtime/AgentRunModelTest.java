package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentRunModelTest {

    @Test
    void definesAllowedRuntimeTransitionsAndTerminalStates() {
        assertThat(AgentRunStatus.CREATED.canTransitionTo(AgentRunStatus.RUNNING)).isTrue();
        assertThat(AgentRunStatus.CREATED.canTransitionTo(AgentRunStatus.COMPLETED)).isFalse();
        assertThat(AgentRunStatus.RUNNING.canTransitionTo(AgentRunStatus.WAITING_APPROVAL)).isTrue();
        assertThat(AgentRunStatus.WAITING_APPROVAL.canTransitionTo(AgentRunStatus.RUNNING)).isTrue();
        assertThat(AgentRunStatus.WAITING_APPROVAL.canTransitionTo(AgentRunStatus.COMPLETED)).isFalse();
        assertThat(AgentRunStatus.COMPLETED.canTransitionTo(AgentRunStatus.RUNNING)).isFalse();
        assertThat(AgentRunStatus.COMPLETED.terminal()).isTrue();
        assertThat(AgentRunStatus.FAILED.terminal()).isTrue();
        assertThat(AgentRunStatus.CANCELLED.terminal()).isTrue();
        assertThat(AgentRunStatus.RUNNING.terminal()).isFalse();
    }

    @Test
    void createsValidOwnedRuntimeAggregate() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        AgentRun run = AgentRun.created(
                "550e8400-e29b-41d4-a716-446655440010",
                ActorIdentity.user("user-1"),
                "session-1",
                "owned:v1:conversation",
                "trace-1",
                "question",
                5,
                now
        );

        assertThat(run.status()).isEqualTo(AgentRunStatus.CREATED);
        assertThat(run.ownerKey()).isEqualTo("user:user-1");
        assertThat(run.currentStep()).isZero();
        assertThat(run.maxSteps()).isEqualTo(5);
        assertThat(run.version()).isZero();
        assertThat(run.lastEventSequence()).isZero();
        assertThat(run.checkpointJson()).isEqualTo("{}");
        assertThat(run.createdAt()).isEqualTo(now);
        assertThat(run.completedAt()).isNull();
    }

    @Test
    void rejectsInvalidTerminalAndWaitingAggregates() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AgentRun created = AgentRun.created(
                "550e8400-e29b-41d4-a716-446655440010",
                ActorIdentity.user("user-1"),
                "session-1",
                "conversation-1",
                "trace-1",
                "question",
                5,
                now
        );

        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRun(
                created.runId(), created.ownerKey(), created.sessionId(), created.conversationId(),
                created.traceId(), created.userMessage(), AgentRunStatus.COMPLETED, 1, 5,
                1, 1, "{}", null, "answer", null, null, null, 0, now, now, null
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRun(
                created.runId(), created.ownerKey(), created.sessionId(), created.conversationId(),
                created.traceId(), created.userMessage(), AgentRunStatus.WAITING_APPROVAL, 1, 5,
                1, 1, "{}", null, null, null, null, null, 0, now, now, null
        ));
    }

    @Test
    void validatesDurableRuntimeEvents() {
        AgentRunEvent event = new AgentRunEvent(
                "run-1",
                1,
                AgentRunEventType.RUN_CREATED,
                "{\"status\":\"CREATED\"}",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        assertThat(event.sequence()).isOne();
        assertThat(event.type()).isEqualTo(AgentRunEventType.RUN_CREATED);
        assertThatIllegalArgumentException().isThrownBy(() -> new AgentRunEvent(
                "run-1", 0, AgentRunEventType.RUN_CREATED, "{}", Instant.now()
        ));
    }

    @Test
    void completesAndFailsOnlyFromWorkerOwnedRunningRun() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AgentRun created = AgentRun.created(
                "550e8400-e29b-41d4-a716-446655440010", ActorIdentity.user("user-1"),
                "session-1", "conversation-1", "trace-1", "question", 5, now
        );
        AgentRun running = created.claim("worker-a", now, now.plusSeconds(30));

        AgentRun completed = running.complete(
                "answer", "worker-a", running.leaseEpoch(), now.plusSeconds(1));
        AgentRun failed = running.fail(
                "model unavailable", "worker-a", running.leaseEpoch(), now.plusSeconds(1));

        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.finalAnswer()).isEqualTo("answer");
        assertThat(completed.leaseOwner()).isNull();
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("model unavailable");
        assertThat(failed.completedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void pausesResumesAndRejectsMatchingApproval() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String approvalId = "550e8400-e29b-41d4-a716-446655440099";
        AgentRun running = AgentRun.created(
                        "550e8400-e29b-41d4-a716-446655440010", ActorIdentity.user("user-1"),
                        "session-1", "conversation-1", "trace-1", "question", 5, now)
                .claim("worker-a", now, now.plusSeconds(30));
        AgentRun waiting = running.waitForApproval(
                approvalId, "{\"toolCall\":{}}", "worker-a",
                running.leaseEpoch(), now.plusSeconds(1));

        assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(waiting.pendingApprovalId()).isEqualTo(approvalId);
        assertThat(waiting.leaseOwner()).isNull();
        AgentRun resumed = waiting.resumeApproval(
                approvalId, "worker-b", now.plusSeconds(2), now.plusSeconds(32));
        AgentRun rejected = waiting.rejectApproval(
                approvalId, "not allowed", now.plusSeconds(2));

        assertThat(resumed.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(resumed.pendingApprovalId()).isNull();
        assertThat(resumed.leaseOwner()).isEqualTo("worker-b");
        assertThat(rejected.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(rejected.errorMessage()).isEqualTo("not allowed");
    }

    @Test
    void cancelsNonTerminalRunsAndReleasesExecutionState() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AgentRun created = AgentRun.created(
                "550e8400-e29b-41d4-a716-446655440010", ActorIdentity.user("user-1"),
                "session-1", "conversation-1", "trace-1", "question", 5, now);
        AgentRun running = created.claim("worker-a", now, now.plusSeconds(30));
        AgentRun waiting = running.waitForApproval(
                "550e8400-e29b-41d4-a716-446655440099", "{\"toolCall\":{}}",
                "worker-a", running.leaseEpoch(), now.plusSeconds(1));

        AgentRun cancelledCreated = created.cancel(now.plusSeconds(2));
        AgentRun cancelledRunning = running.cancel(now.plusSeconds(2));
        AgentRun cancelledWaiting = waiting.cancel(now.plusSeconds(2));

        assertThat(cancelledCreated.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(cancelledRunning.leaseOwner()).isNull();
        assertThat(cancelledWaiting.pendingApprovalId()).isNull();
        assertThat(cancelledWaiting.completedAt()).isEqualTo(now.plusSeconds(2));
    }
}
