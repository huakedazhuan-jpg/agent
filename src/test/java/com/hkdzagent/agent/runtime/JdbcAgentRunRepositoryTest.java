package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAgentRunRepositoryTest {

    private JdbcAgentRunRepository repository;
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:runtime_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE agent_runs (
                    id UUID PRIMARY KEY, owner_key VARCHAR(320) NOT NULL,
                    session_id VARCHAR(256) NOT NULL, conversation_id VARCHAR(1024) NOT NULL,
                    trace_id VARCHAR(128) NOT NULL UNIQUE, user_message CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL, current_step INTEGER NOT NULL,
                    max_steps INTEGER NOT NULL, version BIGINT NOT NULL,
                    last_event_sequence BIGINT NOT NULL, checkpoint JSON NOT NULL,
                    pending_approval_id UUID, final_answer CLOB, error_message CLOB,
                    lease_owner VARCHAR(128), lease_expires_at TIMESTAMP,
                    lease_epoch BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, completed_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_run_events (
                    id UUID PRIMARY KEY, run_id UUID NOT NULL, sequence BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL, payload JSON NOT NULL, created_at TIMESTAMP NOT NULL,
                    UNIQUE (run_id, sequence),
                    FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
                )
                """);
        repository = new JdbcAgentRunRepository(
                new NamedParameterJdbcTemplate(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    void persistsRunEventsAndOwnerScope() {
        AgentRun stored = repository.create(run("user-a"), "{\"source\":\"test\"}");
        repository.appendEvent(stored.runId(), AgentRunEventType.MODEL_STARTED, "{}", now.plusSeconds(1));

        AgentRun reloaded = repository.findById(stored.runId());
        assertThat(reloaded.lastEventSequence()).isEqualTo(2);
        assertThat(reloaded.version()).isZero();
        assertThat(repository.findEventsAfter(stored.runId(), 0, 10))
                .extracting(AgentRunEvent::sequence)
                .containsExactly(1L, 2L);
        assertThat(repository.findByIdAndOwner(stored.runId(), "user:user-b")).isNull();
        assertThat(repository.findRecentByOwner("user:user-a", 10))
                .extracting(AgentRun::runId)
                .containsExactly(stored.runId());
    }

    @Test
    void leasesCanBeReclaimedAfterExpiry() {
        AgentRun stored = repository.create(run("user-a"), "{}");

        AgentRunClaim first = repository.claim(stored.runId(), "worker-a", now, Duration.ofSeconds(30));
        assertThat(first.started()).isTrue();
        assertThat(first.run().leaseEpoch()).isOne();
        assertThat(repository.claim(stored.runId(), "worker-b", now.plusSeconds(1), Duration.ofSeconds(30))).isNull();
        AgentRunClaim reclaimed = repository.claim(
                stored.runId(), "worker-b", now.plusSeconds(31), Duration.ofSeconds(30));

        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.started()).isFalse();
        assertThat(reclaimed.run().leaseOwner()).isEqualTo("worker-b");
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
    void rejectsStaleStateUpdateAndPreservesLatestEventSequence() {
        AgentRun stored = repository.create(run("user-a"), "{}");
        AgentRunClaim claim = repository.claim(stored.runId(), "worker-a", now, Duration.ofSeconds(30));
        AgentRun next = claim.run().advance(
                1, "{\"step\":1}", "worker-a",
                claim.run().leaseEpoch(), now.plusSeconds(1));
        repository.appendEvent(stored.runId(), AgentRunEventType.MODEL_STARTED, "{}", now.plusSeconds(1));

        AgentRun updated = repository.update(next, claim.run().version(), "worker-a");

        assertThat(updated.currentStep()).isOne();
        assertThat(updated.lastEventSequence()).isEqualTo(2);
        assertThat(repository.update(next, claim.run().version(), "worker-a")).isNull();
    }

    @Test
    void atomicallyClaimsOldestExpiredRunningRun() {
        AgentRun oldest = repository.create(run("user-oldest"), "{}");
        AgentRun later = repository.create(run("user-later"), "{}");
        AgentRun neverStarted = repository.create(run("user-created"), "{}");
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

    @Test
    void allowsOnlyOneRecoveryWorkerToClaimExpiredRun() throws Exception {
        AgentRun stored = repository.create(run("user-race"), "{}");
        AgentRunClaim initial = repository.claim(
                stored.runId(), "initial-worker", now, Duration.ofSeconds(5));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AgentRunClaim> first = workers.submit(() -> {
                start.await();
                return repository.claimNextExpired(
                        "recovery-a", now.plusSeconds(6), Duration.ofSeconds(30));
            });
            Future<AgentRunClaim> second = workers.submit(() -> {
                start.await();
                return repository.claimNextExpired(
                        "recovery-b", now.plusSeconds(6), Duration.ofSeconds(30));
            });

            List<AgentRunClaim> successful = java.util.stream.Stream.of(first.get(), second.get())
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(successful).singleElement().satisfies(claim -> {
                assertThat(claim.run().runId()).isEqualTo(stored.runId());
                assertThat(claim.run().leaseEpoch())
                        .isEqualTo(initial.run().leaseEpoch() + 1);
            });
            assertThat(repository.findById(stored.runId()).leaseOwner())
                    .isIn("recovery-a", "recovery-b");
        } finally {
            workers.shutdownNow();
        }
    }

    private AgentRun run(String userId) {
        return AgentRun.created(
                UUID.randomUUID().toString(), ActorIdentity.user(userId), "session-1",
                "conversation-1", UUID.randomUUID().toString(), "question", 5, now
        );
    }
}
