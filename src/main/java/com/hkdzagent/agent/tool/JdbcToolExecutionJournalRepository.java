package com.hkdzagent.agent.tool;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public class JdbcToolExecutionJournalRepository implements ToolExecutionJournalRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;

    public JdbcToolExecutionJournalRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionOperations transactions
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions == null
                ? TransactionOperations.withoutTransaction() : transactions;
    }

    @Override
    public Reservation reserve(ToolExecutionJournalEntry candidate) {
        return transactions.execute(status -> {
            int inserted = jdbcTemplate.update("""
                    INSERT INTO tool_execution_journal (
                        run_id, tool_call_id, tool_name, tool_version, arguments_hash,
                        status, result_status, result_message, execution_token,
                        started_at, completed_at
                    ) VALUES (
                        :runId, :toolCallId, :toolName, :toolVersion, :argumentsHash,
                        :status, NULL, NULL, :executionToken, :startedAt, NULL
                    )
                    ON CONFLICT (run_id, tool_call_id) DO NOTHING
                    """, parameters(candidate));
            ToolExecutionJournalEntry stored = find(candidate.runId(), candidate.toolCallId());
            if (stored == null) {
                throw new IllegalStateException("reserved tool execution could not be read");
            }
            return new Reservation(inserted == 1, stored);
        });
    }

    @Override
    public ToolExecutionJournalEntry find(String runId, String toolCallId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT CAST(run_id AS VARCHAR) AS run_id,
                           tool_call_id, tool_name, tool_version, arguments_hash,
                           status, result_status, result_message,
                           CAST(execution_token AS VARCHAR) AS execution_token,
                           started_at, completed_at
                    FROM tool_execution_journal
                    WHERE run_id = :runId AND tool_call_id = :toolCallId
                    """, new MapSqlParameterSource()
                    .addValue("runId", UUID.fromString(runId))
                    .addValue("toolCallId", toolCallId), this::mapEntry);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    @Override
    public ToolExecutionJournalEntry complete(
            String runId,
            String toolCallId,
            String executionToken,
            ToolResult result,
            Instant completedAt
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE tool_execution_journal
                SET status = 'COMPLETED',
                    result_status = :resultStatus,
                    result_message = :resultMessage,
                    completed_at = :completedAt
                WHERE run_id = :runId
                  AND tool_call_id = :toolCallId
                  AND execution_token = :executionToken
                  AND status = 'STARTED'
                """, new MapSqlParameterSource()
                .addValue("runId", UUID.fromString(runId))
                .addValue("toolCallId", toolCallId)
                .addValue("executionToken", UUID.fromString(executionToken))
                .addValue("resultStatus", result.status().name())
                .addValue("resultMessage", result.message())
                .addValue("completedAt", Timestamp.from(completedAt)));
        return updated == 1 ? find(runId, toolCallId) : null;
    }

    private MapSqlParameterSource parameters(ToolExecutionJournalEntry entry) {
        return new MapSqlParameterSource()
                .addValue("runId", UUID.fromString(entry.runId()))
                .addValue("toolCallId", entry.toolCallId())
                .addValue("toolName", entry.toolName())
                .addValue("toolVersion", entry.toolVersion())
                .addValue("argumentsHash", entry.argumentsHash())
                .addValue("status", entry.status().name())
                .addValue("executionToken", UUID.fromString(entry.executionToken()))
                .addValue("startedAt", Timestamp.from(entry.startedAt()));
    }

    private ToolExecutionJournalEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        String resultStatus = rs.getString("result_status");
        return new ToolExecutionJournalEntry(
                rs.getString("run_id"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                rs.getString("tool_version"),
                rs.getString("arguments_hash"),
                ToolExecutionJournalEntry.Status.valueOf(rs.getString("status")),
                resultStatus == null ? null : ToolResult.Status.valueOf(resultStatus),
                rs.getString("result_message"),
                rs.getString("execution_token"),
                rs.getTimestamp("started_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
