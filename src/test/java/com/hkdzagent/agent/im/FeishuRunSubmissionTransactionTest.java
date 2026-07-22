package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.runtime.AgentRunCoordinator;
import com.hkdzagent.agent.runtime.AgentRunRepository;
import com.hkdzagent.agent.runtime.AgentRuntimeExecutor;
import com.hkdzagent.agent.runtime.AgentRuntimeProperties;
import com.hkdzagent.agent.runtime.AgentRuntimeService;
import com.hkdzagent.agent.runtime.JdbcAgentRunRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.JdbcAgentTraceRepository;
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
import static org.mockito.Mockito.mock;

class FeishuRunSubmissionTransactionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionConfig.class);

    @Test
    void rollsBackNewRunAndTraceWhenInboxClaimWasReplacedBeforeBinding() {
        contextRunner.run(context -> {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            createTables(jdbc);
            FeishuEventInboxRepository inbox = context.getBean(FeishuEventInboxRepository.class);
            Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
            inbox.receive(event("event-stale-binding", startedAt));
            FeishuInboxEvent staleClaim = inbox.claim(
                    "event-stale-binding", startedAt, Duration.ofMinutes(5), 3);
            FeishuInboxEvent replacement = inbox.claim(
                    "event-stale-binding", startedAt.plusSeconds(300),
                    Duration.ofMinutes(5), 3);

            assertThatThrownBy(() -> context.getBean(FeishuRunSubmissionService.class)
                    .findOrCreate(
                            staleClaim,
                            ActorIdentity.feishu("open-1"),
                            "open-1",
                            "feishu:open-1",
                            "hello"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("claim was lost");

            assertThat(replacement.retryCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_traces", Integer.class))
                    .isZero();
            assertThat(inbox.findById("event-stale-binding").runId()).isNull();
        });
    }

    @Test
    void reusesBoundRunWhenProcessRestartsBeforeExecution() {
        contextRunner.run(context -> {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            createTables(jdbc);
            FeishuEventInboxRepository inbox = context.getBean(FeishuEventInboxRepository.class);
            FeishuRunSubmissionService submissions =
                    context.getBean(FeishuRunSubmissionService.class);
            Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
            inbox.receive(event("event-bound-before-crash", startedAt));
            FeishuInboxEvent firstClaim = inbox.claim(
                    "event-bound-before-crash", startedAt, Duration.ofMinutes(5), 3);

            var created = submissions.findOrCreate(
                    firstClaim,
                    ActorIdentity.feishu("open-1"),
                    "open-1",
                    "feishu:open-1",
                    "hello");
            FeishuInboxEvent replacement = inbox.claim(
                    "event-bound-before-crash", startedAt.plusSeconds(300),
                    Duration.ofMinutes(5), 3);
            var recovered = submissions.findOrCreate(
                    replacement,
                    ActorIdentity.feishu("open-1"),
                    "open-1",
                    "feishu:open-1",
                    "hello");

            assertThat(replacement.runId()).isEqualTo(created.runId());
            assertThat(recovered.runId()).isEqualTo(created.runId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class))
                    .isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_traces", Integer.class))
                    .isOne();
        });
    }

    private FeishuInboxEvent event(String eventId, Instant receivedAt) {
        return new FeishuInboxEvent(
                eventId, "im.message.receive_v1", "open-1", "{}",
                FeishuInboxEvent.Status.RECEIVED, receivedAt,
                null, null, receivedAt, 0, null);
    }

    private void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_traces (
                    trace_id VARCHAR(128) PRIMARY KEY, owner_key VARCHAR(320) NOT NULL,
                    session_id VARCHAR(256) NOT NULL, user_message CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL, started_at TIMESTAMP NOT NULL, ended_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_trace_events (
                    id UUID PRIMARY KEY, trace_id VARCHAR(128) NOT NULL,
                    event_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
                    event_index INTEGER NOT NULL DEFAULT 0, step INTEGER NOT NULL DEFAULT 0,
                    tool_name VARCHAR(128), success BOOLEAN, content_preview CLOB,
                    arguments_preview CLOB, error_message CLOB, duration_ms BIGINT,
                    payload JSON NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (trace_id) REFERENCES agent_traces (trace_id) ON DELETE CASCADE
                )
                """);
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
                CREATE TABLE feishu_event_inbox (
                    event_id VARCHAR(256) PRIMARY KEY, event_type VARCHAR(128) NOT NULL,
                    open_id VARCHAR(256), payload JSON NOT NULL, status VARCHAR(32) NOT NULL,
                    received_at TIMESTAMP NOT NULL, claimed_at TIMESTAMP,
                    processed_at TIMESTAMP, next_attempt_at TIMESTAMP,
                    retry_count INTEGER NOT NULL DEFAULT 0, last_error CLOB,
                    run_id UUID, FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE SET NULL
                )
                """);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:feishu_run_binding_" + UUID.randomUUID()
                            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        NamedParameterJdbcTemplate jdbc(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        FeishuEventInboxRepository inboxRepository(
                NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper
        ) {
            return new JdbcFeishuEventInboxRepository(jdbc, objectMapper);
        }

        @Bean
        AgentRunRepository runRepository(
                NamedParameterJdbcTemplate jdbc,
                PlatformTransactionManager transactionManager
        ) {
            return new JdbcAgentRunRepository(
                    jdbc, new TransactionTemplate(transactionManager));
        }

        @Bean
        AgentRuntimeService runtimeService(
                AgentRunRepository repository, ObjectMapper objectMapper
        ) {
            return new AgentRuntimeService(
                    repository, new AgentRuntimeProperties(), objectMapper,
                    Clock.fixed(Instant.parse("2026-01-01T00:05:00Z"), ZoneOffset.UTC));
        }

        @Bean
        AgentTraceRecorder traceRecorder(
                NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper
        ) {
            return new AgentTraceRecorder(
                    new JdbcAgentTraceRepository(jdbc, objectMapper),
                    new AgentTraceSanitizer(160));
        }

        @Bean
        AgentRunCoordinator runCoordinator(
                AgentRuntimeService runtimeService, AgentTraceRecorder traceRecorder
        ) {
            return new AgentRunCoordinator(
                    runtimeService, mock(AgentRuntimeExecutor.class), traceRecorder);
        }

        @Bean
        FeishuRunSubmissionService submissionService(
                FeishuEventInboxRepository inboxRepository,
                AgentRunCoordinator runCoordinator
        ) {
            return new FeishuRunSubmissionService(inboxRepository, runCoordinator);
        }
    }
}
