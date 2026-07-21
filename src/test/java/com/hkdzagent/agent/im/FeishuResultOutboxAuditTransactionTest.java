package com.hkdzagent.agent.im;

import com.hkdzagent.agent.audit.AdminAuditRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeishuResultOutboxAuditTransactionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionConfig.class);

    @Test
    void rollsBackRetryWhenAuditWriteFails() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            createOutboxTable(dataSource);
            JdbcFeishuResultOutboxRepository repository =
                    context.getBean(JdbcFeishuResultOutboxRepository.class);
            FeishuResultOutboxAdminService service =
                    context.getBean(FeishuResultOutboxAdminService.class);
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            FeishuResultOutboxMessage message = new FeishuResultOutboxMessage(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "open-id", "answer", FeishuResultOutboxMessage.Type.FINAL_RESULT,
                    "notification:" + UUID.randomUUID(),
                    FeishuResultOutboxMessage.Status.PENDING,
                    now, null, null, null, null, 0, null);
            repository.enqueue(message);
            repository.claim(message.id(), now, java.time.Duration.ofMinutes(5), 1);
            repository.markFailed(
                    message.id(), "delivery failed", now.plusSeconds(1),
                    now.plusSeconds(30), true);

            assertThatThrownBy(() -> service.retryDead(
                    message.id(), ActorIdentity.user("admin-id")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("audit storage unavailable");

            FeishuResultOutboxMessage afterRollback = repository.findById(message.id());
            assertThat(afterRollback.status()).isEqualTo(FeishuResultOutboxMessage.Status.DEAD);
            assertThat(afterRollback.attemptCount()).isOne();
            assertThat(afterRollback.deadAt()).isEqualTo(now.plusSeconds(1));
        });
    }

    private void createOutboxTable(DataSource dataSource) {
        new JdbcTemplate(dataSource).execute("""
                CREATE TABLE feishu_result_outbox (
                    id UUID PRIMARY KEY,
                    run_id UUID NOT NULL,
                    open_id VARCHAR(256) NOT NULL,
                    message_text CLOB NOT NULL,
                    notification_type VARCHAR(32) NOT NULL,
                    deduplication_key VARCHAR(512) NOT NULL UNIQUE,
                    status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    claimed_at TIMESTAMP,
                    sent_at TIMESTAMP,
                    dead_at TIMESTAMP,
                    next_attempt_at TIMESTAMP,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_error VARCHAR(500)
                )
                """);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:outbox_audit_tx_" + UUID.randomUUID()
                            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    "sa", "");
        }

        @Bean
        NamedParameterJdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcFeishuResultOutboxRepository outboxRepository(
                NamedParameterJdbcTemplate jdbcTemplate
        ) {
            return new JdbcFeishuResultOutboxRepository(jdbcTemplate);
        }

        @Bean
        AdminAuditRepository auditRepository() {
            AdminAuditRepository repository = mock(AdminAuditRepository.class);
            when(repository.save(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new IllegalStateException("audit storage unavailable"));
            return repository;
        }

        @Bean
        FeishuProperties feishuProperties() {
            return new FeishuProperties();
        }

        @Bean
        FeishuResultOutboxAdminService adminService(
                JdbcFeishuResultOutboxRepository outboxRepository,
                AdminAuditRepository auditRepository,
                FeishuProperties properties
        ) {
            return new FeishuResultOutboxAdminService(
                    outboxRepository, auditRepository, properties, java.time.Clock.systemUTC());
        }
    }
}
