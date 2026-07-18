package com.hkdzagent.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAgentTraceRepositoryTest {

    private JdbcAgentTraceRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:trace_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE agent_traces (
                    trace_id VARCHAR(128) PRIMARY KEY,
                    session_id VARCHAR(256) NOT NULL,
                    user_message CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    started_at TIMESTAMP NOT NULL,
                    ended_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE agent_trace_events (
                    id UUID PRIMARY KEY,
                    trace_id VARCHAR(128) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    event_index INTEGER NOT NULL DEFAULT 0,
                    step INTEGER NOT NULL DEFAULT 0,
                    tool_name VARCHAR(128),
                    success BOOLEAN,
                    content_preview CLOB,
                    arguments_preview CLOB,
                    error_message CLOB,
                    duration_ms BIGINT,
                    payload JSON NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (trace_id) REFERENCES agent_traces (trace_id) ON DELETE CASCADE
                )
                """);
        repository = new JdbcAgentTraceRepository(new NamedParameterJdbcTemplate(jdbcTemplate), new ObjectMapper());
    }

    @Test
    void persistsTraceLifecycleAndEvents() {
        AgentTraceRecorder recorder = new AgentTraceRecorder(repository, new AgentTraceSanitizer(80));

        recorder.startTrace("trace-jdbc", "session-jdbc", "user message with api-key=secret");
        recorder.recordModelRequest("trace-jdbc", 1, Map.of(
                "model", "kimi-k2.5",
                "Authorization", "Bearer secret-token"
        ));
        recorder.recordToolCall("trace-jdbc", 1, "httpRequestTool",
                "{\"url\":\"https://query1.finance.yahoo.com/v8/finance/chart/AAPL\"}");
        recorder.recordToolObservation("trace-jdbc", 1, "httpRequestTool", true,
                "quote-data", Duration.ofMillis(45));
        recorder.recordFinalAnswer("trace-jdbc", "AAPL quote summary");
        recorder.finishTrace("trace-jdbc", "COMPLETED");

        AgentTrace stored = repository.findByTraceId("trace-jdbc");

        assertThat(stored).isNotNull();
        assertThat(stored.traceId()).isEqualTo("trace-jdbc");
        assertThat(stored.sessionId()).isEqualTo("session-jdbc");
        assertThat(stored.status()).isEqualTo(TraceStatus.COMPLETED);
        assertThat(stored.endedAt()).isNotNull();
        assertThat(stored.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(stored.events())
                .extracting(event -> event.type().name())
                .containsExactly("MODEL_REQUEST", "TOOL_CALL", "TOOL_OBSERVATION", "FINAL_ANSWER");
        assertThat(stored.events().get(0).metadata()).containsEntry("Authorization", "[redacted]");
        assertThat(stored.events().get(1).toolName()).isEqualTo("httpRequestTool");
        assertThat(stored.events().get(2).success()).isTrue();
        assertThat(stored.events().get(2).durationMs()).isEqualTo(45L);
        assertThat(stored.toString().toLowerCase()).doesNotContain("secret-token", "api-key=secret");
    }

    @Test
    void findRecentReturnsNewestTracesFirst() {
        repository.save(new AgentTrace(
                "trace-old",
                "session-1",
                "old",
                Instant.parse("2026-01-01T00:00:00Z"),
                TraceStatus.RUNNING,
                null,
                List.of()
        ));
        repository.save(new AgentTrace(
                "trace-new",
                "session-1",
                "new",
                Instant.parse("2026-01-01T00:00:01Z"),
                TraceStatus.RUNNING,
                null,
                List.of()
        ));

        List<AgentTrace> recent = repository.findRecent(2);

        assertThat(recent)
                .extracting(AgentTrace::traceId)
                .containsExactly("trace-new", "trace-old");
    }

    @Test
    void ignoresEventsForMissingTraceIds() {
        repository.addEvent("missing-trace", new AgentTraceEvent(
                "missing-trace",
                TraceEventType.ERROR,
                1,
                null,
                false,
                null,
                null,
                "ignored",
                null,
                Map.of()
        ));

        assertThat(repository.findByTraceId("missing-trace")).isNull();
    }
}
