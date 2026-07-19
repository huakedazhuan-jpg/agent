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
                    session_id VARCHAR(256) NOT NULL,
                    trace_id VARCHAR(128),
                    tool_name VARCHAR(128) NOT NULL,
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
        assertThat(reloadedRepository.findPendingBySessionId("session-jdbc"))
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
        assertThat(repository.findPendingBySessionId("session-expired")).isEmpty();
    }

    private ToolConfirmation confirmation(String sessionId, Instant createdAt, Instant expiresAt) {
        return new ToolConfirmation(
                UUID.randomUUID().toString(),
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
