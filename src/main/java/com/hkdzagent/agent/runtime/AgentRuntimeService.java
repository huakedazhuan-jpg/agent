package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.security.ActorIdentity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AgentRuntimeService {

    private final AgentRunRepository repository;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentRuntimeService(
            AgentRunRepository repository,
            AgentRuntimeProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        validateProperties(properties);
    }

    public AgentRun create(
            ActorIdentity owner,
            String sessionId,
            String conversationId,
            String traceId,
            String userMessage
    ) {
        Instant now = clock.instant();
        AgentRun run = AgentRun.created(
                UUID.randomUUID().toString(),
                owner,
                sessionId,
                conversationId,
                traceId,
                userMessage,
                properties.getMaxSteps(),
                now
        );
        return repository.create(run, json(Map.of(
                "traceId", traceId,
                "sessionId", sessionId,
                "conversationId", conversationId
        )));
    }

    public AgentRunClaim claim(String runId, String workerId) {
        return repository.claim(runId, workerId, clock.instant(), properties.getLeaseDuration());
    }

    public AgentRun checkpoint(String runId, String workerId, int step, Object checkpoint) {
        Instant now = clock.instant();
        AgentRun current = requireRun(runId);
        AgentRun next = current.advance(
                step,
                json(checkpoint),
                workerId,
                now,
                now.plus(properties.getLeaseDuration())
        );
        AgentRun persisted = repository.update(next, current.version(), workerId);
        if (persisted == null) {
            throw new IllegalStateException("agent run changed concurrently or worker lease was lost");
        }
        return persisted;
    }

    public AgentRun complete(String runId, String workerId, String answer) {
        Instant now = clock.instant();
        AgentRun current = requireRun(runId);
        AgentRun persisted = repository.update(
                current.complete(answer, workerId, now), current.version(), workerId);
        if (persisted == null) {
            throw new IllegalStateException("agent run changed concurrently or worker lease was lost");
        }
        return persisted;
    }

    public AgentRun fail(String runId, String workerId, String error) {
        Instant now = clock.instant();
        AgentRun current = requireRun(runId);
        AgentRun persisted = repository.update(
                current.fail(error, workerId, now), current.version(), workerId);
        if (persisted == null) {
            throw new IllegalStateException("agent run changed concurrently or worker lease was lost");
        }
        return persisted;
    }

    public AgentRun waitForApproval(
            String runId, String workerId, String approvalId, String checkpointJson
    ) {
        Instant now = clock.instant();
        AgentRun current = requireRun(runId);
        AgentRun persisted = repository.update(
                current.waitForApproval(approvalId, checkpointJson, workerId, now),
                current.version(), workerId);
        if (persisted == null) {
            throw new IllegalStateException("agent run changed concurrently or worker lease was lost");
        }
        return persisted;
    }

    public AgentRun resumeApproval(String runId, String approvalId, String workerId) {
        Instant now = clock.instant();
        AgentRun current = requireRun(runId);
        AgentRun persisted = repository.update(
                current.resumeApproval(
                        approvalId, workerId, now, now.plus(properties.getLeaseDuration())),
                current.version(), null);
        if (persisted == null) {
            throw new IllegalStateException("agent approval was already resumed concurrently");
        }
        return persisted;
    }

    public AgentRun rejectApproval(String runId, String approvalId, String reason) {
        Instant now = clock.instant();
        AgentRun current = requireRun(runId);
        AgentRun persisted = repository.update(
                current.rejectApproval(approvalId, reason, now), current.version(), null);
        if (persisted == null) {
            throw new IllegalStateException("agent approval was already decided concurrently");
        }
        return persisted;
    }

    public AgentRun find(String runId) {
        return repository.findById(runId);
    }

    public AgentRunEvent appendEvent(String runId, AgentRunEventType type, Object payload) {
        AgentRunEvent event = repository.appendEvent(runId, type, json(payload), clock.instant());
        if (event == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        return event;
    }

    public AgentRun findOwned(String runId, ActorIdentity owner) {
        return repository.findByIdAndOwner(runId, owner.key());
    }

    public List<AgentRun> findRecent(ActorIdentity owner, int limit) {
        return repository.findRecentByOwner(owner.key(), Math.min(Math.max(1, limit), 100));
    }

    public List<AgentRunEvent> replayEvents(String runId, long afterSequence) {
        return repository.findEventsAfter(runId, afterSequence, properties.getEventReplayLimit());
    }

    private AgentRun requireRun(String runId) {
        AgentRun run = repository.findById(runId);
        if (run == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        return run;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("runtime payload must be JSON serializable", exception);
        }
    }

    private void validateProperties(AgentRuntimeProperties candidate) {
        if (candidate.getMaxSteps() < 1) {
            throw new IllegalArgumentException("agent.runtime.max-steps must be positive");
        }
        if (candidate.getLeaseDuration() == null
                || candidate.getLeaseDuration().isZero()
                || candidate.getLeaseDuration().isNegative()) {
            throw new IllegalArgumentException("agent.runtime.lease-duration must be positive");
        }
        if (candidate.getEventReplayLimit() < 1) {
            throw new IllegalArgumentException("agent.runtime.event-replay-limit must be positive");
        }
    }
}
