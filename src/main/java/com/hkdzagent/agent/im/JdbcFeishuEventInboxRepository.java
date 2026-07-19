package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class JdbcFeishuEventInboxRepository implements FeishuEventInboxRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcFeishuEventInboxRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean receive(FeishuInboxEvent event) {
        try {
            jdbcTemplate.update("""
                INSERT INTO feishu_event_inbox (
                    event_id,
                    event_type,
                    open_id,
                    payload,
                    status,
                    received_at,
                    claimed_at,
                    processed_at,
                    next_attempt_at,
                    retry_count,
                    last_error
                ) VALUES (
                    :eventId,
                    :eventType,
                    :openId,
                    CAST(:payload AS JSON),
                    :status,
                    :receivedAt,
                    :claimedAt,
                    :processedAt,
                    :nextAttemptAt,
                    :retryCount,
                    :lastError
                )
                """,
                parameters(event));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public FeishuInboxEvent claim(
            String eventId,
            Instant now,
            Duration processingTimeout,
            int maxAttempts
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE feishu_event_inbox
                SET status = 'PROCESSING',
                    claimed_at = :now,
                    processed_at = NULL,
                    next_attempt_at = NULL,
                    retry_count = retry_count + 1
                WHERE event_id = :eventId
                  AND retry_count < :maxAttempts
                  AND (
                    status = 'RECEIVED'
                    OR (status = 'RETRYABLE' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (status = 'PROCESSING' AND claimed_at <= :staleBefore)
                  )
                """,
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("now", Timestamp.from(now))
                        .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                        .addValue("maxAttempts", maxAttempts));
        return updated == 1 ? findById(eventId) : null;
    }

    @Override
    public void markProcessed(String eventId, Instant processedAt) {
        jdbcTemplate.update("""
                UPDATE feishu_event_inbox
                SET status = 'PROCESSED',
                    processed_at = :processedAt,
                    next_attempt_at = NULL,
                    last_error = NULL
                WHERE event_id = :eventId
                  AND status = 'PROCESSING'
                """,
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("processedAt", Timestamp.from(processedAt)));
    }

    @Override
    public void markFailed(
            String eventId,
            String error,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean terminal
    ) {
        jdbcTemplate.update("""
                UPDATE feishu_event_inbox
                SET status = :status,
                    processed_at = CASE WHEN :terminal THEN :failedAt ELSE NULL END,
                    next_attempt_at = CASE WHEN :terminal THEN NULL ELSE :nextAttemptAt END,
                    last_error = :lastError
                WHERE event_id = :eventId
                  AND status = 'PROCESSING'
                """,
                new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("status", terminal ? "DEAD" : "RETRYABLE")
                        .addValue("terminal", terminal)
                        .addValue("failedAt", Timestamp.from(failedAt))
                        .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                        .addValue("lastError", error));
    }

    @Override
    public List<String> findReadyEventIds(
            Instant now,
            Duration processingTimeout,
            int maxAttempts,
            int limit
    ) {
        return jdbcTemplate.queryForList("""
                SELECT event_id
                FROM feishu_event_inbox
                WHERE retry_count < :maxAttempts
                  AND (
                    status = 'RECEIVED'
                    OR (status = 'RETRYABLE' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (status = 'PROCESSING' AND claimed_at <= :staleBefore)
                  )
                ORDER BY received_at ASC, event_id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                        .addValue("maxAttempts", maxAttempts)
                        .addValue("limit", Math.max(1, limit)),
                String.class);
    }

    @Override
    public int deadLetterExhaustedStale(
            Instant now,
            Duration processingTimeout,
            int maxAttempts
    ) {
        return jdbcTemplate.update("""
                UPDATE feishu_event_inbox
                SET status = 'DEAD',
                    processed_at = :now,
                    next_attempt_at = NULL,
                    last_error = 'processing lease expired after final attempt'
                WHERE status = 'PROCESSING'
                  AND retry_count >= :maxAttempts
                  AND claimed_at <= :staleBefore
                """,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                        .addValue("maxAttempts", maxAttempts));
    }

    @Override
    public FeishuInboxEvent findById(String eventId) {
        List<FeishuInboxEvent> events = jdbcTemplate.query("""
                SELECT event_id,
                       event_type,
                       open_id,
                       payload,
                       status,
                       received_at,
                       claimed_at,
                       processed_at,
                       next_attempt_at,
                       retry_count,
                       last_error
                FROM feishu_event_inbox
                WHERE event_id = :eventId
                """,
                new MapSqlParameterSource("eventId", eventId),
                this::mapEvent);
        return events.isEmpty() ? null : events.get(0);
    }

    private MapSqlParameterSource parameters(FeishuInboxEvent event) {
        return new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("eventType", event.eventType())
                .addValue("openId", event.openId())
                .addValue("payload", event.payload())
                .addValue("status", event.status().name())
                .addValue("receivedAt", timestamp(event.receivedAt()))
                .addValue("claimedAt", timestamp(event.claimedAt()))
                .addValue("processedAt", timestamp(event.processedAt()))
                .addValue("nextAttemptAt", timestamp(event.nextAttemptAt()))
                .addValue("retryCount", event.retryCount())
                .addValue("lastError", event.lastError());
    }

    private FeishuInboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new FeishuInboxEvent(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("open_id"),
                normalizePayload(rs.getString("payload")),
                FeishuInboxEvent.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("received_at").toInstant(),
                instant(rs.getTimestamp("claimed_at")),
                instant(rs.getTimestamp("processed_at")),
                instant(rs.getTimestamp("next_attempt_at")),
                rs.getInt("retry_count"),
                rs.getString("last_error")
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "{}";
        }
        try {
            var parsed = objectMapper.readTree(payload);
            return parsed.isTextual() ? parsed.asText() : payload;
        } catch (Exception ignored) {
            return payload;
        }
    }
}
