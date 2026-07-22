package com.hkdzagent.agent.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    AgentRun renewLease(
            String runId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Duration leaseDuration
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
