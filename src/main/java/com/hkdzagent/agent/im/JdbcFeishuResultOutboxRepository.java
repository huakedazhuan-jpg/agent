package com.hkdzagent.agent.im;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class JdbcFeishuResultOutboxRepository
        implements FeishuResultOutboxRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcFeishuResultOutboxRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean enqueue(FeishuResultOutboxMessage message) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO feishu_result_outbox (
                        id, run_id, open_id, message_text,
                        notification_type, deduplication_key, status,
                        created_at, claimed_at, sent_at, dead_at, next_attempt_at,
                        attempt_count, last_error
                    ) VALUES (
                        CAST(:id AS UUID), CAST(:runId AS UUID), :openId, :messageText,
                        :notificationType, :deduplicationKey, :status,
                        :createdAt, :claimedAt, :sentAt, :deadAt, :nextAttemptAt,
                        :attemptCount, :lastError
                    )
                    """, parameters(message));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public FeishuResultOutboxMessage claim(
            String id, Instant now, Duration processingTimeout, int maxAttempts
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE feishu_result_outbox
                SET status = 'PROCESSING',
                    claimed_at = :now,
                    sent_at = NULL, dead_at = NULL,
                    next_attempt_at = NULL,
                    attempt_count = attempt_count + 1
                WHERE id = CAST(:id AS UUID)
                  AND attempt_count < :maxAttempts
                  AND (
                    status = 'PENDING'
                    OR (status = 'RETRYABLE'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (status = 'PROCESSING' AND claimed_at <= :staleBefore)
                  )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", Timestamp.from(now))
                .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                .addValue("maxAttempts", maxAttempts));
        return updated == 1 ? findById(id) : null;
    }

    @Override
    public void markSent(String id, Instant sentAt) {
        jdbcTemplate.update("""
                UPDATE feishu_result_outbox
                SET status = 'SENT', sent_at = :sentAt,
                    next_attempt_at = NULL, last_error = NULL
                WHERE id = CAST(:id AS UUID) AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sentAt", Timestamp.from(sentAt)));
    }

    @Override
    public void markFailed(
            String id, String error, Instant failedAt,
            Instant nextAttemptAt, boolean terminal
    ) {
        jdbcTemplate.update("""
                UPDATE feishu_result_outbox
                SET status = :status,
                    sent_at = NULL,
                    dead_at = CASE WHEN :terminal THEN :failedAt ELSE NULL END,
                    next_attempt_at = CASE WHEN :terminal THEN NULL ELSE :nextAttemptAt END,
                    last_error = :lastError
                WHERE id = CAST(:id AS UUID) AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", terminal ? "DEAD" : "RETRYABLE")
                .addValue("terminal", terminal)
                .addValue("failedAt", Timestamp.from(failedAt))
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("lastError", error));
    }

    @Override
    public List<String> findReadyIds(
            Instant now, Duration processingTimeout, int maxAttempts, int limit
    ) {
        return jdbcTemplate.queryForList("""
                SELECT CAST(id AS VARCHAR)
                FROM feishu_result_outbox
                WHERE attempt_count < :maxAttempts
                  AND (
                    status = 'PENDING'
                    OR (status = 'RETRYABLE'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (status = 'PROCESSING' AND claimed_at <= :staleBefore)
                  )
                ORDER BY created_at ASC, id ASC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                .addValue("maxAttempts", maxAttempts)
                .addValue("limit", Math.max(1, limit)), String.class);
    }

    @Override
    public int deadLetterExhaustedStale(
            Instant now, Duration processingTimeout, int maxAttempts
    ) {
        return jdbcTemplate.update("""
                UPDATE feishu_result_outbox
                SET status = 'DEAD', dead_at = :now,
                    next_attempt_at = NULL,
                    last_error = 'delivery lease expired after final attempt'
                WHERE status = 'PROCESSING'
                  AND attempt_count >= :maxAttempts
                  AND claimed_at <= :staleBefore
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                .addValue("maxAttempts", maxAttempts));
    }

    @Override
    public long countByStatus(FeishuResultOutboxMessage.Status status) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM feishu_result_outbox
                WHERE status = :status
                """, new MapSqlParameterSource("status", status.name()), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countReady(
            Instant now, Duration processingTimeout, int maxAttempts
    ) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM feishu_result_outbox
                WHERE attempt_count < :maxAttempts
                  AND (
                    status = 'PENDING'
                    OR (status = 'RETRYABLE'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (status = 'PROCESSING' AND claimed_at <= :staleBefore)
                  )
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("staleBefore", Timestamp.from(now.minus(processingTimeout)))
                .addValue("maxAttempts", maxAttempts), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Instant findOldestOutstandingCreatedAt() {
        Timestamp oldest = jdbcTemplate.queryForObject("""
                SELECT MIN(created_at)
                FROM feishu_result_outbox
                WHERE status IN ('PENDING', 'PROCESSING', 'RETRYABLE')
                """, new MapSqlParameterSource(), Timestamp.class);
        return instant(oldest);
    }

    @Override
    public List<FeishuResultOutboxMessage> findByStatus(
            FeishuResultOutboxMessage.Status status, int limit
    ) {
        return jdbcTemplate.query("""
                SELECT CAST(id AS VARCHAR) AS id,
                       CAST(run_id AS VARCHAR) AS run_id,
                       open_id, message_text, notification_type,
                       deduplication_key, status, created_at,
                       claimed_at, sent_at, dead_at, next_attempt_at,
                       attempt_count, last_error
                FROM feishu_result_outbox
                WHERE status = :status
                ORDER BY created_at ASC, id ASC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("limit", Math.max(1, limit)), this::mapMessage);
    }

    @Override
    public FeishuResultOutboxMessage retryDead(String id, Instant retryAt) {
        int updated = jdbcTemplate.update("""
                UPDATE feishu_result_outbox
                SET status = 'RETRYABLE', claimed_at = NULL,
                    sent_at = NULL, dead_at = NULL,
                    next_attempt_at = :retryAt, attempt_count = 0
                WHERE id = CAST(:id AS UUID) AND status = 'DEAD'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("retryAt", Timestamp.from(retryAt)));
        return updated == 1 ? findById(id) : null;
    }

    @Override
    public FeishuResultOutboxMessage findById(String id) {
        return find("WHERE id = CAST(:value AS UUID)", id);
    }

    @Override
    public FeishuResultOutboxMessage findByDeduplicationKey(String deduplicationKey) {
        return find("WHERE deduplication_key = :value", deduplicationKey);
    }

    private FeishuResultOutboxMessage find(String predicate, String value) {
        List<FeishuResultOutboxMessage> messages = jdbcTemplate.query("""
                SELECT CAST(id AS VARCHAR) AS id,
                       CAST(run_id AS VARCHAR) AS run_id,
                       open_id, message_text, notification_type,
                       deduplication_key, status, created_at,
                       claimed_at, sent_at, dead_at, next_attempt_at,
                       attempt_count, last_error
                FROM feishu_result_outbox
                """ + predicate,
                new MapSqlParameterSource("value", value), this::mapMessage);
        return messages.isEmpty() ? null : messages.get(0);
    }

    private MapSqlParameterSource parameters(FeishuResultOutboxMessage message) {
        return new MapSqlParameterSource()
                .addValue("id", message.id())
                .addValue("runId", message.runId())
                .addValue("openId", message.openId())
                .addValue("messageText", message.text())
                .addValue("notificationType", message.type().name())
                .addValue("deduplicationKey", message.deduplicationKey())
                .addValue("status", message.status().name())
                .addValue("createdAt", timestamp(message.createdAt()))
                .addValue("claimedAt", timestamp(message.claimedAt()))
                .addValue("sentAt", timestamp(message.sentAt()))
                .addValue("deadAt", timestamp(message.deadAt()))
                .addValue("nextAttemptAt", timestamp(message.nextAttemptAt()))
                .addValue("attemptCount", message.attemptCount())
                .addValue("lastError", message.lastError());
    }

    private FeishuResultOutboxMessage mapMessage(
            ResultSet rs, int rowNum
    ) throws SQLException {
        return new FeishuResultOutboxMessage(
                rs.getString("id"), rs.getString("run_id"),
                rs.getString("open_id"), rs.getString("message_text"),
                FeishuResultOutboxMessage.Type.valueOf(rs.getString("notification_type")),
                rs.getString("deduplication_key"),
                FeishuResultOutboxMessage.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("claimed_at")),
                instant(rs.getTimestamp("sent_at")),
                instant(rs.getTimestamp("dead_at")),
                instant(rs.getTimestamp("next_attempt_at")),
                rs.getInt("attempt_count"), rs.getString("last_error"));
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
