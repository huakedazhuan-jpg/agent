package com.hkdzagent.agent.memory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcMemoryProcessingJobRepository implements MemoryProcessingJobRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcMemoryProcessingJobRepository(
            NamedParameterJdbcTemplate jdbc, TransactionOperations transactions
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void enqueue(
            String ownerKey,
            String externalConversationId,
            String runId,
            MemoryProcessingJob.JobType type
    ) {
        Instant now = Instant.now();
        String deduplicationKey = runId + ":" + type.name();
        jdbc.update("""
                INSERT INTO memory_processing_jobs (
                    id, owner_key, conversation_id, run_id, job_type,
                    deduplication_key, status, attempt_count, next_attempt_at,
                    created_at, updated_at
                )
                SELECT :id, :ownerKey, conversation.id, :runId, :jobType,
                       :deduplicationKey, 'PENDING', 0, :now, :now, :now
                FROM agent_conversations conversation
                WHERE conversation.owner_key = :ownerKey
                  AND conversation.channel = 'chat_memory'
                  AND conversation.external_conversation_id = :externalConversationId
                ON CONFLICT (deduplication_key) DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("ownerKey", ownerKey)
                        .addValue("externalConversationId", externalConversationId)
                        .addValue("runId", UUID.fromString(runId))
                        .addValue("jobType", type.name())
                        .addValue("deduplicationKey", deduplicationKey)
                        .addValue("now", Timestamp.from(now)));
    }

    @Override
    public List<MemoryProcessingJob> claim(int limit, Instant now) {
        return transactions.execute(status -> jdbc.query("""
                WITH candidates AS (
                    SELECT id
                    FROM memory_processing_jobs
                    WHERE status IN ('PENDING', 'RETRYABLE')
                      AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE memory_processing_jobs job
                SET status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    updated_at = :now
                FROM candidates
                WHERE job.id = candidates.id
                RETURNING job.*
                """, new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("limit", Math.max(1, Math.min(limit, 100))),
                this::map));
    }

    @Override
    public void complete(UUID id, Instant now) {
        jdbc.update("""
                UPDATE memory_processing_jobs
                SET status = 'COMPLETED', last_error = NULL, updated_at = :now
                WHERE id = :id AND status = 'PROCESSING'
                """, params(id, now));
    }

    @Override
    public void fail(UUID id, String error, Instant retryAt, int maxAttempts) {
        jdbc.update("""
                UPDATE memory_processing_jobs
                SET status = CASE WHEN attempt_count >= :maxAttempts THEN 'DEAD'
                                  ELSE 'RETRYABLE' END,
                    next_attempt_at = :retryAt,
                    last_error = :error,
                    updated_at = :now
                WHERE id = :id AND status = 'PROCESSING'
                """, new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("maxAttempts", maxAttempts)
                        .addValue("retryAt", Timestamp.from(retryAt))
                        .addValue("error", truncate(error, 4000))
                        .addValue("now", Timestamp.from(Instant.now())));
    }

    @Override
    public boolean retry(UUID id, Instant now) {
        return jdbc.update("""
                UPDATE memory_processing_jobs
                SET status = 'RETRYABLE', next_attempt_at = :now,
                    last_error = NULL, updated_at = :now
                WHERE id = :id AND status IN ('DEAD', 'RETRYABLE')
                """, params(id, now)) == 1;
    }

    private MemoryProcessingJob map(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new MemoryProcessingJob(
                rs.getObject("id", UUID.class),
                rs.getString("owner_key"),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("run_id", UUID.class).toString(),
                MemoryProcessingJob.JobType.valueOf(rs.getString("job_type")),
                rs.getString("deduplication_key"),
                MemoryProcessingJob.Status.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private MapSqlParameterSource params(UUID id, Instant now) {
        return new MapSqlParameterSource("id", id)
                .addValue("now", Timestamp.from(now));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
