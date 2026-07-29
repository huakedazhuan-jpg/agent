package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentRunRepositoryTest {

    private final InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void createsRunAndInitialEventAtomically() {
        AgentRun stored = repository.create(run("user-a", now), "{\"source\":\"test\"}");

        assertThat(stored.lastEventSequence()).isOne();
        assertThat(repository.findEventsAfter(stored.runId(), 0, 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.sequence()).isOne();
                    assertThat(event.type()).isEqualTo(AgentRunEventType.RUN_CREATED);
                });
    }

    @Test
    void enforcesOwnerScopeLeaseAndOptimisticVersion() {
        AgentRun stored = repository.create(run("user-a", now), "{}");

        assertThat(repository.findByIdAndOwner(stored.runId(), "user:user-b")).isNull();
        AgentRunClaim first = repository.claim(stored.runId(), "worker-a", now, Duration.ofSeconds(30));
        assertThat(first.started()).isTrue();
        assertThat(first.run().leaseEpoch()).isOne();
        assertThat(repository.claim(stored.runId(), "worker-b", now.plusSeconds(1), Duration.ofSeconds(30))).isNull();

        AgentRun advanced = first.run().advance(
                1, "{\"step\":1}", "worker-a",
                first.run().leaseEpoch(), now.plusSeconds(2));
        assertThat(repository.update(advanced, first.run().version(), "worker-a")).isNotNull();
        assertThat(repository.update(advanced, first.run().version(), "worker-a")).isNull();

        AgentRunClaim reclaimed = repository.claim(
                stored.runId(), "worker-b", now.plusSeconds(31), Duration.ofSeconds(30));
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.started()).isFalse();
        assertThat(reclaimed.run().leaseEpoch()).isEqualTo(2);
        assertThat(repository.appendWorkerEvent(
                stored.runId(), "worker-a", first.run().leaseEpoch(),
                AgentRunEventType.TOKEN_DELTA, "{}", now.plusSeconds(32))).isNull();
        assertThat(repository.appendWorkerEvent(
                stored.runId(), "worker-b", reclaimed.run().leaseEpoch(),
                AgentRunEventType.TOKEN_DELTA, "{}", now.plusSeconds(32))).isNotNull();
        assertThat(repository.renewLease(
                stored.runId(), "worker-a", first.run().leaseEpoch(),
                now.plusSeconds(32), Duration.ofSeconds(30))).isNull();
        AgentRun renewed = repository.renewLease(
                stored.runId(), "worker-b", reclaimed.run().leaseEpoch(),
                now.plusSeconds(32), Duration.ofSeconds(30));
        assertThat(renewed.leaseEpoch()).isEqualTo(reclaimed.run().leaseEpoch());
        assertThat(renewed.leaseExpiresAt()).isEqualTo(now.plusSeconds(62));
    }

    @Test
    void appendsContiguousEventsWithoutChangingStateVersion() {
        AgentRun stored = repository.create(run("user-a", now), "{}");
        long stateVersion = stored.version();

        repository.appendEvent(stored.runId(), AgentRunEventType.MODEL_STARTED, "{}", now.plusSeconds(1));
        repository.appendEvent(stored.runId(), AgentRunEventType.TOKEN_DELTA, "{\"delta\":\"hi\"}", now.plusSeconds(2));

        AgentRun reloaded = repository.findById(stored.runId());
        assertThat(reloaded.version()).isEqualTo(stateVersion);
        assertThat(reloaded.lastEventSequence()).isEqualTo(3);
        assertThat(repository.findEventsAfter(stored.runId(), 1, 10))
                .extracting(AgentRunEvent::sequence)
                .containsExactly(2L, 3L);
    }

    @Test
    void atomicallyClaimsOldestExpiredRunningRun() {
        AgentRun oldest = repository.create(run("user-oldest", now), "{}");
        AgentRun later = repository.create(run("user-later", now.plusSeconds(1)), "{}");
        AgentRun neverStarted = repository.create(run("user-created", now.plusSeconds(2)), "{}");
        AgentRunClaim oldestInitial = repository.claim(
                oldest.runId(), "worker-old", now, Duration.ofSeconds(10));
        AgentRunClaim laterInitial = repository.claim(
                later.runId(), "worker-later", now, Duration.ofSeconds(30));

        AgentRunClaim recovered = repository.claimNextExpired(
                "recovery-worker", now.plusSeconds(10), Duration.ofSeconds(20));

        assertThat(recovered).isNotNull();
        assertThat(recovered.started()).isFalse();
        assertThat(recovered.run().runId()).isEqualTo(oldest.runId());
        assertThat(recovered.run().leaseOwner()).isEqualTo("recovery-worker");
        assertThat(recovered.run().leaseEpoch())
                .isEqualTo(oldestInitial.run().leaseEpoch() + 1);
        assertThat(recovered.run().leaseExpiresAt()).isEqualTo(now.plusSeconds(30));
        assertThat(repository.claimNextExpired(
                "another-worker", now.plusSeconds(10), Duration.ofSeconds(20))).isNull();
        assertThat(repository.findById(later.runId()).leaseEpoch())
                .isEqualTo(laterInitial.run().leaseEpoch());
        assertThat(repository.findById(neverStarted.runId()).status())
                .isEqualTo(AgentRunStatus.CREATED);
    }

    private AgentRun run(String userId, Instant createdAt) {
        return AgentRun.created(
                UUID.randomUUID().toString(), ActorIdentity.user(userId), "session-1",
                "conversation-1", "trace-1", "question", 5, createdAt
        );
    }
}
