package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.JdbcToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.im.FeishuResultOutboxRepository;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentApprovalPauseTransactionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionConfig.class);

    @Test
    void rollsBackApprovalRunPauseAndEventWhenOutboxWriteFails() {
        contextRunner.run(context -> {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            createTables(jdbc);
            AgentRuntimeService runtime = context.getBean(AgentRuntimeService.class);
            AgentRun created = runtime.create(
                    ActorIdentity.feishu("open-1"), "open-1", "conversation-1",
                    UUID.randomUUID().toString(), "run command");
            AgentRunClaim claim = runtime.claim(created.runId(), "worker-1");

            assertThatThrownBy(() -> context.getBean(AgentApprovalPauseService.class).pause(
                    created, claim.run().leaseOwner(), 1,
                    "commandExecuteTool", "{\"command\":\"mvn test\"}",
                    "{\"schemaVersion\":1,\"toolCall\":{}}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("outbox unavailable");

            AgentRun afterRollback = runtime.find(created.runId());
            assertThat(afterRollback.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(afterRollback.pendingApprovalId()).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tool_approvals", Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_run_events WHERE event_type = 'APPROVAL_REQUIRED'",
                    Integer.class)).isZero();
        });
    }

    private void createTables(JdbcTemplate jdbc) {
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
                    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_run_events (
                    id UUID PRIMARY KEY, run_id UUID NOT NULL, sequence BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL, payload JSON NOT NULL,
                    created_at TIMESTAMP NOT NULL, UNIQUE (run_id, sequence),
                    FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE tool_approvals (
                    id UUID PRIMARY KEY, owner_key VARCHAR(320) NOT NULL,
                    session_id VARCHAR(256) NOT NULL, trace_id VARCHAR(128), run_id UUID,
                    tool_name VARCHAR(128) NOT NULL, tool_version VARCHAR(64) NOT NULL,
                    tool_call_id VARCHAR(128), request_hash VARCHAR(128) NOT NULL,
                    request_payload JSON NOT NULL, arguments_preview CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL, decision_reason CLOB,
                    expires_at TIMESTAMP NOT NULL, decided_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL
                )
                """);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:approval_pause_tx_" + UUID.randomUUID()
                            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        NamedParameterJdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AgentRuntimeService runtimeService(
                NamedParameterJdbcTemplate jdbc,
                PlatformTransactionManager transactionManager,
                ObjectMapper objectMapper,
                Clock clock
        ) {
            JdbcAgentRunRepository repository = new JdbcAgentRunRepository(
                    jdbc, new TransactionTemplate(transactionManager));
            return new AgentRuntimeService(
                    repository, new AgentRuntimeProperties(), objectMapper, clock);
        }

        @Bean
        ToolConfirmationRepository confirmationRepository(
                NamedParameterJdbcTemplate jdbc,
                ObjectMapper objectMapper
        ) {
            return new JdbcToolConfirmationRepository(jdbc, objectMapper);
        }

        @Bean
        ToolConfirmationService confirmationService(
                ToolConfirmationRepository repository,
                Clock clock
        ) {
            return new ToolConfirmationService(
                    repository, new AgentTraceSanitizer(160),
                    Duration.ofMinutes(15), clock);
        }

        @Bean
        FeishuResultOutboxService outboxService(Clock clock) {
            FeishuResultOutboxRepository repository = mock(FeishuResultOutboxRepository.class);
            when(repository.enqueue(any())).thenThrow(
                    new IllegalStateException("outbox unavailable"));
            return new FeishuResultOutboxService(repository, clock);
        }

        @Bean
        AgentApprovalPauseService pauseService(
                ToolConfirmationService confirmationService,
                AgentRuntimeService runtimeService,
                ObjectMapper objectMapper,
                FeishuResultOutboxService outboxService
        ) {
            return new AgentApprovalPauseService(
                    confirmationService, runtimeService, new AgentTraceSanitizer(160),
                    objectMapper, outboxService);
        }
    }
}
