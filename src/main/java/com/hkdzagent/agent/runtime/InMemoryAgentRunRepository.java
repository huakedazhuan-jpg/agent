package com.hkdzagent.agent.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class InMemoryAgentRunRepository implements AgentRunRepository {

    private final Map<String, AgentRun> runs = new LinkedHashMap<>();
    private final Map<String, List<AgentRunEvent>> events = new LinkedHashMap<>();

    @Override
    public synchronized AgentRun create(AgentRun run, String initialEventPayloadJson) {
        if (runs.containsKey(run.runId())) {
            throw new IllegalStateException("agent run already exists: " + run.runId());
        }
        AgentRun persisted = run.withEventSequence(1, run.createdAt());
        runs.put(run.runId(), persisted);
        events.put(run.runId(), new ArrayList<>(List.of(new AgentRunEvent(
                run.runId(),
                1,
                AgentRunEventType.RUN_CREATED,
                initialEventPayloadJson,
                run.createdAt()
        ))));
        return persisted;
    }

    @Override
    public synchronized AgentRun findById(String runId) {
        return runs.get(runId);
    }

    @Override
    public synchronized AgentRun findByIdAndOwner(String runId, String ownerKey) {
        AgentRun run = runs.get(runId);
        return run != null && run.ownerKey().equals(ownerKey) ? run : null;
    }

    @Override
    public synchronized List<AgentRun> findRecentByOwner(String ownerKey, int limit) {
        List<AgentRun> matching = runs.values().stream()
                .filter(run -> run.ownerKey().equals(ownerKey))
                .toList();
        List<AgentRun> recent = new ArrayList<>();
        for (int index = matching.size() - 1; index >= 0 && recent.size() < Math.max(1, limit); index--) {
            recent.add(matching.get(index));
        }
        return List.copyOf(recent);
    }

    @Override
    public synchronized AgentRunClaim claim(
            String runId,
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        AgentRun current = runs.get(runId);
        if (current == null) {
            return null;
        }
        boolean started = current.status() == AgentRunStatus.CREATED;
        AgentRun claimed;
        try {
            claimed = current.claim(workerId, now, now.plus(requireLeaseDuration(leaseDuration)));
        } catch (IllegalStateException exception) {
            return null;
        }
        runs.put(runId, claimed);
        return new AgentRunClaim(claimed, started);
    }

    @Override
    public synchronized AgentRunClaim claimNextExpired(
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        Duration validLeaseDuration = requireLeaseDuration(leaseDuration);
        AgentRun current = runs.values().stream()
                .filter(run -> run.status() == AgentRunStatus.RUNNING)
                .filter(run -> run.leaseExpiresAt() != null && !run.leaseExpiresAt().isAfter(now))
                .min(Comparator.comparing(AgentRun::leaseExpiresAt)
                        .thenComparing(AgentRun::createdAt)
                        .thenComparing(AgentRun::runId))
                .orElse(null);
        if (current == null) {
            return null;
        }
        AgentRun claimed = current.claim(workerId, now, now.plus(validLeaseDuration));
        runs.put(claimed.runId(), claimed);
        return new AgentRunClaim(claimed, false);
    }

    @Override
    public synchronized AgentRunRecoveryEvidence findRecoveryEvidence(String runId) {
        if (!runs.containsKey(runId)) {
            return null;
        }
        List<AgentRunEvent> runEvents = events.getOrDefault(runId, List.of());
        boolean toolStarted = runEvents.stream()
                .anyMatch(event -> event.type() == AgentRunEventType.TOOL_STARTED);
        int attempts = Math.toIntExact(runEvents.stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_RECOVERY_STARTED)
                .count());
        return new AgentRunRecoveryEvidence(toolStarted, attempts);
    }

    @Override
    public synchronized AgentRun renewLease(
            String runId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Duration leaseDuration
    ) {
        AgentRun current = runs.get(runId);
        if (current == null) {
            return null;
        }
        AgentRun renewed;
        try {
            renewed = current.renewLease(
                    workerId, leaseEpoch, now, now.plus(requireLeaseDuration(leaseDuration)));
        } catch (IllegalStateException exception) {
            return null;
        }
        runs.put(runId, renewed);
        return renewed;
    }

    @Override
    public synchronized <T> T executeWithActiveLease(
            String runId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Supplier<T> action
    ) {
        AgentRun current = runs.get(runId);
        if (current == null || !current.holdsLease(workerId, leaseEpoch, now)) {
            return null;
        }
        return action.get();
    }

    @Override
    public synchronized <T> T executeWithWaitingApproval(
            String runId,
            String approvalId,
            Supplier<T> action
    ) {
        AgentRun current = runs.get(runId);
        if (current == null
                || current.status() != AgentRunStatus.WAITING_APPROVAL
                || !Objects.equals(approvalId, current.pendingApprovalId())) {
            return null;
        }
        return action.get();
    }

    @Override
    public synchronized AgentRun update(AgentRun run, long expectedVersion, String requiredLeaseOwner) {
        AgentRun current = runs.get(run.runId());
        if (current == null || current.version() != expectedVersion || run.version() != expectedVersion + 1) {
            return null;
        }
        if (requiredLeaseOwner != null && !requiredLeaseOwner.equals(current.leaseOwner())) {
            return null;
        }
        AgentRun persisted = run.synchronizeEventSequence(current.lastEventSequence());
        runs.put(run.runId(), persisted);
        return persisted;
    }

    @Override
    public synchronized AgentRunEvent appendEvent(
            String runId,
            AgentRunEventType type,
            String payloadJson,
            Instant createdAt
    ) {
        AgentRun current = runs.get(runId);
        if (current == null) {
            return null;
        }
        long sequence = current.lastEventSequence() + 1;
        AgentRunEvent event = new AgentRunEvent(runId, sequence, type, payloadJson, createdAt);
        events.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(event);
        runs.put(runId, current.withEventSequence(sequence, createdAt));
        return event;
    }

    @Override
    public synchronized AgentRunEvent appendWorkerEvent(
            String runId,
            String workerId,
            long leaseEpoch,
            AgentRunEventType type,
            String payloadJson,
            Instant createdAt
    ) {
        AgentRun current = runs.get(runId);
        if (current == null || !current.holdsLease(workerId, leaseEpoch, createdAt)) {
            return null;
        }
        return appendEvent(runId, type, payloadJson, createdAt);
    }

    @Override
    public synchronized List<AgentRunEvent> findEventsAfter(String runId, long afterSequence, int limit) {
        return events.getOrDefault(runId, List.of()).stream()
                .filter(event -> event.sequence() > Math.max(0, afterSequence))
                .limit(Math.max(1, limit))
                .toList();
    }

    private Duration requireLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("agent run lease duration must be positive");
        }
        return leaseDuration;
    }
}
