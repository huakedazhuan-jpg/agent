package com.hkdzagent.agent.audit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuditRepositoryTest {

    @Test
    void inMemoryRepositoryStoresEventsNewestFirst() {
        InMemoryAdminAuditRepository repository = new InMemoryAdminAuditRepository();
        AdminAuditEvent older = event(Instant.parse("2026-01-01T00:00:00Z"));
        AdminAuditEvent newer = event(Instant.parse("2026-01-01T00:00:01Z"));

        repository.save(older);
        repository.save(newer);

        assertThat(repository.findRecent(10)).containsExactly(newer, older);
    }

    @Test
    void jdbcRepositoryPersistsAndReadsBoundedEvents() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_audit_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE admin_audit_events (
                    id UUID PRIMARY KEY,
                    actor_key VARCHAR(320) NOT NULL,
                    action VARCHAR(96) NOT NULL,
                    resource_type VARCHAR(96) NOT NULL,
                    resource_id VARCHAR(256) NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    detail VARCHAR(500),
                    created_at TIMESTAMP NOT NULL
                )
                """);
        JdbcAdminAuditRepository repository = new JdbcAdminAuditRepository(
                new NamedParameterJdbcTemplate(jdbc));
        AdminAuditEvent older = event(Instant.parse("2026-01-01T00:00:00Z"));
        AdminAuditEvent newer = event(Instant.parse("2026-01-01T00:00:01Z"));

        assertThat(repository.save(older)).isEqualTo(older);
        assertThat(repository.save(newer)).isEqualTo(newer);

        assertThat(repository.findRecent(1)).containsExactly(newer);
    }

    private AdminAuditEvent event(Instant createdAt) {
        return new AdminAuditEvent(
                UUID.randomUUID().toString(), "user:admin-id",
                "FEISHU_OUTBOX_RETRY", "FEISHU_RESULT_OUTBOX",
                UUID.randomUUID().toString(), AdminAuditEvent.Outcome.SUCCEEDED,
                "manual retry", createdAt);
    }
}
