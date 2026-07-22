package com.hkdzagent.agent.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

public interface AgentRunRepository {

    AgentRun create(AgentRun run, String initialEventPayloadJson);

    AgentRun findById(String runId);

    AgentRun findByIdAndOwner(String runId, String ownerKey);

    List<AgentRun> findRecentByOwner(String ownerKey, int limit);

    AgentRunClaim claim(String runId, String workerId, Instant now, Duration leaseDuration);

    AgentRunClaim claimNextExpired(
            String workerId,
            Instant now,
            Duration leaseDuration
    );

    AgentRunRecoveryEvidence findRecoveryEvidence(String runId);

    AgentRun renewLease(
            String runId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Duration leaseDuration
    );

    /**
     * Runs a persistence-only action while holding the run's execution fence.
     * The action must not perform external I/O because the run row remains locked.
     */
    <T> T executeWithActiveLease(
            String runId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Supplier<T> action
    );

    /**
     * Runs a persistence-only approval decision while holding the waiting run row lock.
     * Callers must acquire resources in run-then-approval order.
     */
    <T> T executeWithWaitingApproval(
            String runId,
            String approvalId,
            Supplier<T> action
    );

    AgentRun update(AgentRun run, long expectedVersion, String requiredLeaseOwner);

    AgentRunEvent appendEvent(
            String runId,
            AgentRunEventType type,
            String payloadJson,
            Instant createdAt
    );

    AgentRunEvent appendWorkerEvent(
            String runId,
            String workerId,
            long leaseEpoch,
            AgentRunEventType type,
            String payloadJson,
            Instant createdAt
    );

    List<AgentRunEvent> findEventsAfter(String runId, long afterSequence, int limit);
}
