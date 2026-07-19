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
import java.util.UUID;

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
        assertThat(repository.claim(stored.runId(), "worker-b", now.plusSeconds(1), Duration.ofSeconds(30))).isNull();
        AgentRunClaim reclaimed = repository.claim(
                stored.runId(), "worker-b", now.plusSeconds(31), Duration.ofSeconds(30));

        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.started()).isFalse();
        assertThat(reclaimed.run().leaseOwner()).isEqualTo("worker-b");
    }

    @Test
    void rejectsStaleStateUpdateAndPreservesLatestEventSequence() {
        AgentRun stored = repository.create(run("user-a"), "{}");
        AgentRunClaim claim = repository.claim(stored.runId(), "worker-a", now, Duration.ofSeconds(30));
        AgentRun next = claim.run().advance(1, "{\"step\":1}", "worker-a", now.plusSeconds(1));
        repository.appendEvent(stored.runId(), AgentRunEventType.MODEL_STARTED, "{}", now.plusSeconds(1));

        AgentRun updated = repository.update(next, claim.run().version(), "worker-a");

        assertThat(updated.currentStep()).isOne();
        assertThat(updated.lastEventSequence()).isEqualTo(2);
        assertThat(repository.update(next, claim.run().version(), "worker-a")).isNull();
    }

    private AgentRun run(String userId) {
        return AgentRun.created(
                UUID.randomUUID().toString(), ActorIdentity.user(userId), "session-1",
                "conversation-1", UUID.randomUUID().toString(), "question", 5, now
        );
    }
}
