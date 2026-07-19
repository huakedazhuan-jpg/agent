package com.hkdzagent.agent.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JdbcAgentTraceRepository implements AgentTraceRepository {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAgentTraceRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentTrace save(AgentTrace trace) {
        MapSqlParameterSource parameters = traceParameters(trace);
        int updated = jdbcTemplate.update("""
                UPDATE agent_traces
                SET owner_key = :ownerKey,
                    session_id = :sessionId,
                    user_message = :userMessage,
                    status = :status,
                    started_at = :startedAt,
                    ended_at = :endedAt
                WHERE trace_id = :traceId
                """,
                parameters);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO agent_traces (
                        trace_id, owner_key, session_id, user_message, status, started_at, ended_at
                    )
                    VALUES (
                        :traceId, :ownerKey, :sessionId, :userMessage, :status, :startedAt, :endedAt
                    )
                    """,
                    parameters);
        }
        jdbcTemplate.update("DELETE FROM agent_trace_events WHERE trace_id = :traceId",
                new MapSqlParameterSource("traceId", trace.traceId()));
        for (AgentTraceEvent event : trace.events()) {
            addEvent(trace.traceId(), event);
        }
        return trace;
    }

    @Override
    public AgentTrace findByTraceId(String traceId) {
        try {
            AgentTrace trace = jdbcTemplate.queryForObject("""
                    SELECT trace_id, owner_key, session_id, user_message, status, started_at, ended_at
                    FROM agent_traces
                    WHERE trace_id = :traceId
                    """,
                    new MapSqlParameterSource("traceId", traceId),
                    this::mapTrace);
            if (trace == null) {
                return null;
            }
            return withEvents(trace);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public AgentTrace findByTraceIdAndOwner(String traceId, String ownerKey) {
        try {
            AgentTrace trace = jdbcTemplate.queryForObject("""
                    SELECT trace_id, owner_key, session_id, user_message, status, started_at, ended_at
                    FROM agent_traces
                    WHERE trace_id = :traceId
                      AND owner_key = :ownerKey
                    """,
                    new MapSqlParameterSource()
                            .addValue("traceId", traceId)
                            .addValue("ownerKey", ownerKey),
                    this::mapTrace);
            return trace == null ? null : withEvents(trace);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<AgentTrace> findRecent(int limit) {
        int safeLimit = Math.max(1, limit);
        List<String> traceIds = jdbcTemplate.queryForList("""
                SELECT trace_id
                FROM agent_traces
                ORDER BY started_at DESC, trace_id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", safeLimit),
                String.class);
        return traceIds.stream()
                .map(this::findByTraceId)
                .toList();
    }

    @Override
    public List<AgentTrace> findRecentByOwner(String ownerKey, int limit) {
        int safeLimit = Math.max(1, limit);
        List<String> traceIds = jdbcTemplate.queryForList("""
                SELECT trace_id
                FROM agent_traces
                WHERE owner_key = :ownerKey
                ORDER BY started_at DESC, trace_id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("limit", safeLimit),
                String.class);
        return traceIds.stream()
                .map(this::findByTraceId)
                .toList();
    }

    @Override
    public synchronized void addEvent(String traceId, AgentTraceEvent event) {
        if (!exists(traceId)) {
            return;
        }
        int eventIndex = nextEventIndex(traceId);
        jdbcTemplate.update("""
                INSERT INTO agent_trace_events (
                    id,
                    trace_id,
                    event_type,
                    status,
                    event_index,
                    step,
                    tool_name,
                    success,
                    content_preview,
                    arguments_preview,
                    error_message,
                    duration_ms,
                    payload
                )
                VALUES (
                    :id,
                    :traceId,
                    :eventType,
                    :status,
                    :eventIndex,
                    :step,
                    :toolName,
                    :success,
                    :contentPreview,
                    :argumentsPreview,
                    :errorMessage,
                    :durationMs,
                    CAST(:payload AS JSON)
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("traceId", traceId)
                        .addValue("eventType", event.type().name())
                        .addValue("status", "RECORDED")
                        .addValue("eventIndex", eventIndex)
                        .addValue("step", event.step())
                        .addValue("toolName", event.toolName())
                        .addValue("success", event.success())
                        .addValue("contentPreview", event.contentPreview())
                        .addValue("argumentsPreview", event.argumentsPreview())
                        .addValue("errorMessage", event.errorMessage())
                        .addValue("durationMs", event.durationMs())
                        .addValue("payload", writeMetadata(event.metadata())));
    }

    @Override
    public synchronized void finish(String traceId, TraceStatus status) {
        jdbcTemplate.update("""
                UPDATE agent_traces
                SET status = :status,
                    ended_at = :endedAt
                WHERE trace_id = :traceId
                """,
                new MapSqlParameterSource()
                        .addValue("traceId", traceId)
                        .addValue("status", status.name())
                        .addValue("endedAt", Timestamp.from(Instant.now())));
    }

    private boolean exists(String traceId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_traces
                WHERE trace_id = :traceId
                """,
                new MapSqlParameterSource("traceId", traceId),
                Integer.class);
        return count != null && count > 0;
    }

    private int nextEventIndex(String traceId) {
        Integer maxIndex = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(event_index), -1)
                FROM agent_trace_events
                WHERE trace_id = :traceId
                """,
                new MapSqlParameterSource("traceId", traceId),
                Integer.class);
        return maxIndex == null ? 0 : maxIndex + 1;
    }

    private AgentTrace mapTrace(ResultSet rs, int rowNum) throws SQLException {
        return new AgentTrace(
                rs.getString("trace_id"),
                rs.getString("owner_key"),
                rs.getString("session_id"),
                rs.getString("user_message"),
                rs.getTimestamp("started_at").toInstant(),
                TraceStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("ended_at")),
                List.of()
        );
    }

    private AgentTrace withEvents(AgentTrace trace) {
        return new AgentTrace(
                trace.traceId(),
                trace.ownerKey(),
                trace.sessionId(),
                trace.userMessage(),
                trace.startedAt(),
                trace.status(),
                trace.endedAt(),
                findEvents(trace.traceId())
        );
    }

    private MapSqlParameterSource traceParameters(AgentTrace trace) {
        return new MapSqlParameterSource()
                .addValue("traceId", trace.traceId())
                .addValue("ownerKey", trace.ownerKey())
                .addValue("sessionId", trace.sessionId())
                .addValue("userMessage", trace.userMessage())
                .addValue("status", trace.status().name())
                .addValue("startedAt", Timestamp.from(trace.startedAt()))
                .addValue("endedAt", timestamp(trace.endedAt()));
    }

    private List<AgentTraceEvent> findEvents(String traceId) {
        return jdbcTemplate.query("""
                SELECT trace_id,
                       event_type,
                       step,
                       tool_name,
                       success,
                       content_preview,
                       arguments_preview,
                       error_message,
                       duration_ms,
                       payload
                FROM agent_trace_events
                WHERE trace_id = :traceId
                ORDER BY event_index ASC, created_at ASC, id ASC
                """,
                new MapSqlParameterSource("traceId", traceId),
                this::mapEvent);
    }

    private AgentTraceEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AgentTraceEvent(
                rs.getString("trace_id"),
                TraceEventType.valueOf(rs.getString("event_type")),
                rs.getInt("step"),
                rs.getString("tool_name"),
                booleanOrNull(rs, "success"),
                rs.getString("content_preview"),
                rs.getString("arguments_preview"),
                rs.getString("error_message"),
                longOrNull(rs, "duration_ms"),
                readMetadata(rs.getString("payload"))
        );
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize trace metadata", e);
        }
    }

    private Map<String, Object> readMetadata(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(payload, Object.class);
            if (parsed instanceof String nestedPayload) {
                return objectMapper.readValue(nestedPayload, METADATA_TYPE);
            }
            return objectMapper.convertValue(parsed, METADATA_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize trace metadata", e);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Boolean booleanOrNull(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
