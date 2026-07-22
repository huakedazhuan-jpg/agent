package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AgentRunFencingContractTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsUnscopedWorkerEventAfterLeaseExpiry() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRun stored = repository.create(run(), "{}");
        AgentRunClaim claim = repository.claim(
                stored.runId(), "worker-a", now, Duration.ofSeconds(30));

        AgentRunEvent staleEvent = repository.appendWorkerEvent(
                stored.runId(),
                "worker-a",
                claim.run().leaseEpoch(),
                AgentRunEventType.TOKEN_DELTA,
                "{\"delta\":\"late\"}",
                now.plusSeconds(31)
        );

        assertThat(staleEvent)
                .as("an event without a current Worker fence must not be accepted after lease expiry")
                .isNull();
    }

    @Test
    void runModelCarriesMonotonicLeaseEpoch() {
        assertThat(Arrays.stream(AgentRun.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("a claim generation is required to fence an earlier Worker with the same ID")
                .contains("leaseEpoch");
    }

    @Test
    void repositoryExposesWorkerScopedEventAppend() {
        assertThatCode(() -> AgentRunRepository.class.getMethod(
                "appendWorkerEvent",
                String.class,
                String.class,
                long.class,
                AgentRunEventType.class,
                String.class,
                Instant.class
        )).as("Worker events must carry run ID, Worker ID, and lease epoch")
                .doesNotThrowAnyException();
    }

    @Test
    void repositoryExposesLeaseHeartbeatWithFence() {
        assertThatCode(() -> AgentRunRepository.class.getMethod(
                "renewLease",
                String.class,
                String.class,
                long.class,
                Instant.class,
                Duration.class
        )).as("lease renewal must reject stale Worker generations")
                .doesNotThrowAnyException();
    }

    @Test
    void runtimeSeparatesSystemEventsFromWorkerEvents() {
        assertThatCode(() -> AgentRuntimeService.class.getMethod(
                "appendSystemEvent",
                String.class,
                AgentRunEventType.class,
                Object.class
        )).as("scheduler and administrator events need an explicit non-Worker path")
                .doesNotThrowAnyException();

        assertThatCode(() -> AgentRuntimeService.class.getMethod(
                "appendWorkerEvent",
                String.class,
                String.class,
                long.class,
                AgentRunEventType.class,
                Object.class
        )).as("model and tool events must be fenced by the active Worker generation")
                .doesNotThrowAnyException();
    }

    private AgentRun run() {
        return AgentRun.created(
                UUID.randomUUID().toString(),
                ActorIdentity.user("fencing-user"),
                "fencing-session",
                "fencing-conversation",
                UUID.randomUUID().toString(),
                "verify stale Worker fencing",
                5,
                now
        );
    }
}
