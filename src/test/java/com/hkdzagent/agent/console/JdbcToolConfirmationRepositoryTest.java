package com.hkdzagent.agent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcToolConfirmationRepositoryTest {

    private JdbcToolConfirmationRepository repository;
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tool_approval_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE tool_approvals (
                    id UUID PRIMARY KEY,
                    owner_key VARCHAR(320) NOT NULL,
                    session_id VARCHAR(256) NOT NULL,
                    trace_id VARCHAR(128),
                    run_id UUID,
                    tool_name VARCHAR(128) NOT NULL,
                    tool_version VARCHAR(64) NOT NULL,
                    tool_call_id VARCHAR(128),
                    request_hash VARCHAR(128) NOT NULL,
                    request_payload JSON NOT NULL,
                    arguments_preview CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    decision_reason CLOB,
                    expires_at TIMESTAMP NOT NULL,
                    decided_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        this.jdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        repository = new JdbcToolConfirmationRepository(this.jdbcTemplate, new ObjectMapper());
    }

    @Test
    void persistsAndReloadsPendingApproval() {
        ToolConfirmation pending = confirmation(
                "session-jdbc",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z")
        );

        repository.save(pending);
        JdbcToolConfirmationRepository reloadedRepository = new JdbcToolConfirmationRepository(
                jdbcTemplate,
                new ObjectMapper()
        );

        assertThat(reloadedRepository.findById(pending.id())).isEqualTo(pending);
        assertThat(reloadedRepository.findPendingByOwnerAndSessionId("user:test", "session-jdbc"))
                .containsExactly(pending);
    }

    @Test
    void atomicallyAllowsOnlyOneDecisionFromPending() {
        ToolConfirmation pending = confirmation(
                "session-race",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z")
        );
        repository.save(pending);
        Instant decisionTime = Instant.parse("2026-01-01T00:01:00Z");

        ToolConfirmation approved = repository.decidePending(
                pending.id(),
                ToolConfirmation.Status.APPROVED,
                "approved",
                decisionTime
        );
        ToolConfirmation rejectedAfterApproval = repository.decidePending(
                pending.id(),
                ToolConfirmation.Status.REJECTED,
                "too late",
                decisionTime.plusSeconds(1)
        );

        assertThat(approved.status()).isEqualTo(ToolConfirmation.Status.APPROVED);
        assertThat(approved.decidedAt()).isEqualTo(decisionTime);
        assertThat(rejectedAfterApproval).isNull();
        assertThat(repository.findById(pending.id()).status())
                .isEqualTo(ToolConfirmation.Status.APPROVED);
    }

    @Test
    void cancelledApprovalIsDurableAndRejectsLateApproval() {
        ToolConfirmation pending = confirmation(
                "session-cancelled",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z"));
        repository.save(pending);

        ToolConfirmation cancelled = repository.decidePending(
                pending.id(), ToolConfirmation.Status.CANCELLED,
                "run cancelled", Instant.parse("2026-01-01T00:01:00Z"));
        ToolConfirmation lateApproval = repository.decidePending(
                pending.id(), ToolConfirmation.Status.APPROVED,
                "too late", Instant.parse("2026-01-01T00:01:01Z"));

        assertThat(cancelled.status()).isEqualTo(ToolConfirmation.Status.CANCELLED);
        assertThat(lateApproval).isNull();
        assertThat(repository.findById(pending.id()).status())
                .isEqualTo(ToolConfirmation.Status.CANCELLED);
    }

    @Test
    void expiresPendingApprovalsAndPreventsLateDecision() {
        ToolConfirmation pending = confirmation(
                "session-expired",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z")
        );
        repository.save(pending);

        int expired = repository.expirePendingBefore(Instant.parse("2026-01-01T00:06:00Z"));
        ToolConfirmation lateDecision = repository.decidePending(
                pending.id(),
                ToolConfirmation.Status.APPROVED,
                "late approval",
                Instant.parse("2026-01-01T00:06:00Z")
        );

        assertThat(expired).isOne();
        assertThat(lateDecision).isNull();
        assertThat(repository.findById(pending.id()).status())
                .isEqualTo(ToolConfirmation.Status.EXPIRED);
        assertThat(repository.findPendingByOwnerAndSessionId("user:test", "session-expired")).isEmpty();
    }

    @Test
    void pendingQueriesIsolateSameSessionAcrossOwners() {
        ToolConfirmation userA = confirmation(
                "user:a",
                "shared-session",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z")
        );
        ToolConfirmation userB = confirmation(
                "user:b",
                "shared-session",
                Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("2026-01-01T00:15:01Z")
        );
        repository.save(userA);
        repository.save(userB);

        assertThat(repository.findPendingByOwnerAndSessionId("user:a", "shared-session"))
                .containsExactly(userA);
        assertThat(repository.findPendingByOwnerAndSessionId("user:b", "shared-session"))
                .containsExactly(userB);
    }

    @Test
    void persistsInvocationBindingInsteadOfRehashingTheDisplayPreview() {
        String argumentsHash = "a".repeat(64);
        ToolConfirmation bound = new ToolConfirmation(
                UUID.randomUUID().toString(), "user:test", "session-bound", "trace-bound",
                "550e8400-e29b-41d4-a716-446655440000", "commandExecuteTool",
                "2.1.0", "call-42", argumentsHash, "{\"command\":\"[redacted]\"}",
                ToolConfirmation.Status.PENDING, null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z"), null
        );

        repository.save(bound);

        assertThat(repository.findById(bound.id())).isEqualTo(bound);
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT request_hash FROM tool_approvals WHERE id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                        "id", UUID.fromString(bound.id())),
                String.class
        );
        assertThat(storedHash).isEqualTo(argumentsHash);
    }

    private ToolConfirmation confirmation(String sessionId, Instant createdAt, Instant expiresAt) {
        return confirmation("user:test", sessionId, createdAt, expiresAt);
    }

    private ToolConfirmation confirmation(
            String ownerKey,
            String sessionId,
            Instant createdAt,
            Instant expiresAt
    ) {
        return new ToolConfirmation(
                UUID.randomUUID().toString(),
                ownerKey,
                sessionId,
                "trace-jdbc",
                "commandExecuteTool",
                "{\"command\":\"mvn test\"}",
                ToolConfirmation.Status.PENDING,
                null,
                createdAt,
                expiresAt,
                null
        );
    }
}
