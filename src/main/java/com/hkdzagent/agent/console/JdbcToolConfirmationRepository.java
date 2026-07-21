package com.hkdzagent.agent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JdbcToolConfirmationRepository implements ToolConfirmationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcToolConfirmationRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolConfirmation save(ToolConfirmation confirmation) {
        jdbcTemplate.update("""
                INSERT INTO tool_approvals (
                    id,
                    owner_key,
                    session_id,
                    trace_id,
                    run_id,
                    tool_name,
                    tool_version,
                    tool_call_id,
                    request_hash,
                    request_payload,
                    arguments_preview,
                    status,
                    decision_reason,
                    expires_at,
                    decided_at,
                    created_at
                ) VALUES (
                    :id,
                    :ownerKey,
                    :sessionId,
                    :traceId,
                    :runId,
                    :toolName,
                    :toolVersion,
                    :toolCallId,
                    :requestHash,
                    CAST(:requestPayload AS JSON),
                    :argumentsPreview,
                    :status,
                    :decisionReason,
                    :expiresAt,
                    :decidedAt,
                    :createdAt
                )
                """,
                parameters(confirmation)
                        .addValue("requestHash", confirmation.argumentsHash())
                        .addValue("requestPayload", writePayload(confirmation.argumentsPreview())));
        return confirmation;
    }

    @Override
    public List<ToolConfirmation> findPendingByOwnerAndSessionId(String ownerKey, String sessionId) {
        return jdbcTemplate.query("""
                SELECT id,
                       owner_key,
                       session_id,
                       trace_id,
                       CAST(run_id AS VARCHAR) AS run_id,
                       tool_name,
                       tool_version,
                       tool_call_id,
                       request_hash,
                       arguments_preview,
                       status,
                       decision_reason,
                       created_at,
                       expires_at,
                       decided_at
                FROM tool_approvals
                WHERE owner_key = :ownerKey
                  AND session_id = :sessionId
                  AND status = 'PENDING'
                ORDER BY created_at ASC, id ASC
                """,
                new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("sessionId", sessionId),
                this::mapConfirmation);
    }

    @Override
    public ToolConfirmation findById(String confirmationId) {
        List<ToolConfirmation> matches = jdbcTemplate.query("""
                SELECT id,
                       owner_key,
                       session_id,
                       trace_id,
                       CAST(run_id AS VARCHAR) AS run_id,
                       tool_name,
                       tool_version,
                       tool_call_id,
                       request_hash,
                       arguments_preview,
                       status,
                       decision_reason,
                       created_at,
                       expires_at,
                       decided_at
                FROM tool_approvals
                WHERE id = :id
                """,
                new MapSqlParameterSource("id", UUID.fromString(confirmationId)),
                this::mapConfirmation);
        return matches.isEmpty() ? null : matches.get(0);
    }

    @Override
    public List<ToolConfirmation> findByStatus(ToolConfirmation.Status status, int limit) {
        return jdbcTemplate.query("""
                SELECT id,
                       owner_key,
                       session_id,
                       trace_id,
                       CAST(run_id AS VARCHAR) AS run_id,
                       tool_name,
                       tool_version,
                       tool_call_id,
                       request_hash,
                       arguments_preview,
                       status,
                       decision_reason,
                       created_at,
                       expires_at,
                       decided_at
                FROM tool_approvals
                WHERE status = :status
                  AND run_id IS NOT NULL
                ORDER BY decided_at ASC, id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("status", status.name())
                        .addValue("limit", Math.max(1, limit)),
                this::mapConfirmation);
    }

    @Override
    public ToolConfirmation decidePending(
            String confirmationId,
            ToolConfirmation.Status status,
            String decisionReason,
            Instant decidedAt
    ) {
        if (status == ToolConfirmation.Status.PENDING || status == ToolConfirmation.Status.EXPIRED) {
            throw new IllegalArgumentException("decision status must be APPROVED or REJECTED");
        }
        int updated = jdbcTemplate.update("""
                UPDATE tool_approvals
                SET status = :status,
                    decision_reason = :decisionReason,
                    decided_at = :decidedAt
                WHERE id = :id
                  AND status = 'PENDING'
                  AND expires_at > :decidedAt
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.fromString(confirmationId))
                        .addValue("status", status.name())
                        .addValue("decisionReason", decisionReason)
                        .addValue("decidedAt", Timestamp.from(decidedAt)));
        return updated == 1 ? findById(confirmationId) : null;
    }

    @Override
    public int expirePendingBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                UPDATE tool_approvals
                SET status = 'EXPIRED',
                    decision_reason = 'approval expired',
                    decided_at = expires_at
                WHERE status = 'PENDING'
                  AND expires_at <= :cutoff
                """,
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    private MapSqlParameterSource parameters(ToolConfirmation confirmation) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.fromString(confirmation.id()))
                .addValue("ownerKey", confirmation.ownerKey())
                .addValue("sessionId", confirmation.sessionId())
                .addValue("traceId", confirmation.traceId())
                .addValue("runId", nullableUuid(confirmation.runId()))
                .addValue("toolName", confirmation.toolName())
                .addValue("toolVersion", confirmation.toolVersion())
                .addValue("toolCallId", confirmation.toolCallId())
                .addValue("argumentsPreview", confirmation.argumentsPreview())
                .addValue("status", confirmation.status().name())
                .addValue("decisionReason", confirmation.decisionReason())
                .addValue("createdAt", Timestamp.from(confirmation.createdAt()))
                .addValue("expiresAt", Timestamp.from(confirmation.expiresAt()))
                .addValue("decidedAt", timestamp(confirmation.decidedAt()));
    }

    private ToolConfirmation mapConfirmation(ResultSet rs, int rowNum) throws SQLException {
        return new ToolConfirmation(
                rs.getString("id"),
                rs.getString("owner_key"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("run_id"),
                rs.getString("tool_name"),
                rs.getString("tool_version"),
                rs.getString("tool_call_id"),
                rs.getString("request_hash"),
                rs.getString("arguments_preview"),
                ToolConfirmation.Status.valueOf(rs.getString("status")),
                rs.getString("decision_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                instant(rs.getTimestamp("decided_at"))
        );
    }

    private String writePayload(String argumentsPreview) {
        try {
            return objectMapper.writeValueAsString(Map.of("argumentsPreview", argumentsPreview));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize tool approval payload", e);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
