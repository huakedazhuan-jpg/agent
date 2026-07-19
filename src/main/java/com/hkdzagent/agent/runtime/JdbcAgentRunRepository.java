package com.hkdzagent.agent.runtime;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcAgentRunRepository implements AgentRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;

    public JdbcAgentRunRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionOperations transactions
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions == null ? TransactionOperations.withoutTransaction() : transactions;
    }

    @Override
    public AgentRun create(AgentRun run, String initialEventPayloadJson) {
        return transactions.execute(status -> {
            AgentRun persisted = run.withEventSequence(1, run.createdAt());
            jdbcTemplate.update("""
                    INSERT INTO agent_runs (
                        id, owner_key, session_id, conversation_id, trace_id, user_message,
                        status, current_step, max_steps, version, last_event_sequence,
                        checkpoint, pending_approval_id, final_answer, error_message,
                        lease_owner, lease_expires_at, created_at, updated_at, completed_at
                    ) VALUES (
                        :id, :ownerKey, :sessionId, :conversationId, :traceId, :userMessage,
                        :status, :currentStep, :maxSteps, :version, :lastEventSequence,
                        CAST(:checkpoint AS JSON), :pendingApprovalId, :finalAnswer, :errorMessage,
                        :leaseOwner, :leaseExpiresAt, :createdAt, :updatedAt, :completedAt
                    )
                    """, parameters(persisted));
            insertEvent(new AgentRunEvent(
                    persisted.runId(),
                    1,
                    AgentRunEventType.RUN_CREATED,
                    initialEventPayloadJson,
                    persisted.createdAt()
            ));
            return persisted;
        });
    }

    @Override
    public AgentRun findById(String runId) {
        return find("WHERE id = :id", new MapSqlParameterSource("id", uuid(runId)));
    }

    @Override
    public AgentRun findByIdAndOwner(String runId, String ownerKey) {
        return find(
                "WHERE id = :id AND owner_key = :ownerKey",
                new MapSqlParameterSource()
                        .addValue("id", uuid(runId))
                        .addValue("ownerKey", ownerKey)
        );
    }

    @Override
    public List<AgentRun> findRecentByOwner(String ownerKey, int limit) {
        List<String> runIds = jdbcTemplate.queryForList("""
                SELECT CAST(id AS VARCHAR)
                FROM agent_runs
                WHERE owner_key = :ownerKey
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("limit", Math.max(1, limit)),
                String.class);
        return runIds.stream().map(this::findById).toList();
    }

    @Override
    public AgentRunClaim claim(
            String runId,
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        requireLeaseDuration(leaseDuration);
        return transactions.execute(status -> {
            AgentRun current = findForUpdate(runId);
            if (current == null) {
                return null;
            }
            boolean started = current.status() == AgentRunStatus.CREATED;
            AgentRun claimed;
            try {
                claimed = current.claim(workerId, now, now.plus(leaseDuration));
            } catch (IllegalStateException exception) {
                return null;
            }
            if (updateRow(claimed, current.version(), null) != 1) {
                return null;
            }
            return new AgentRunClaim(findById(runId), started);
        });
    }

    @Override
    public AgentRun update(AgentRun run, long expectedVersion, String requiredLeaseOwner) {
        if (run.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("updated run version must increment exactly once");
        }
        int updated = updateRow(run, expectedVersion, requiredLeaseOwner);
        return updated == 1 ? findById(run.runId()) : null;
    }

    @Override
    public AgentRunEvent appendEvent(
            String runId,
            AgentRunEventType type,
            String payloadJson,
            Instant createdAt
    ) {
        return transactions.execute(status -> {
            AgentRun current = findForUpdate(runId);
            if (current == null) {
                return null;
            }
            AgentRun next = current.withEventSequence(current.lastEventSequence() + 1, createdAt);
            int updated = jdbcTemplate.update("""
                    UPDATE agent_runs
                    SET last_event_sequence = :lastEventSequence,
                        updated_at = :updatedAt
                    WHERE id = :id
                      AND last_event_sequence = :expectedSequence
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", uuid(runId))
                            .addValue("lastEventSequence", next.lastEventSequence())
                            .addValue("expectedSequence", current.lastEventSequence())
                            .addValue("updatedAt", timestamp(next.updatedAt())));
            if (updated != 1) {
                throw new IllegalStateException("failed to reserve next event sequence for run " + runId);
            }
            AgentRunEvent event = new AgentRunEvent(
                    runId,
                    next.lastEventSequence(),
                    type,
                    payloadJson,
                    createdAt
            );
            insertEvent(event);
            return event;
        });
    }

    @Override
    public List<AgentRunEvent> findEventsAfter(String runId, long afterSequence, int limit) {
        return jdbcTemplate.query("""
                SELECT CAST(run_id AS VARCHAR) AS run_id,
                       sequence,
                       event_type,
                       payload,
                       created_at
                FROM agent_run_events
                WHERE run_id = :runId
                  AND sequence > :afterSequence
                ORDER BY sequence ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("runId", uuid(runId))
                        .addValue("afterSequence", Math.max(0, afterSequence))
                        .addValue("limit", Math.max(1, limit)),
                this::mapEvent);
    }

    private AgentRun findForUpdate(String runId) {
        return find("WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", uuid(runId)));
    }

    private AgentRun find(String whereClause, MapSqlParameterSource parameters) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT CAST(id AS VARCHAR) AS id,
                           owner_key,
                           session_id,
                           conversation_id,
                           trace_id,
                           user_message,
                           status,
                           current_step,
                           max_steps,
                           version,
                           last_event_sequence,
                           checkpoint,
                           CAST(pending_approval_id AS VARCHAR) AS pending_approval_id,
                           final_answer,
                           error_message,
                           lease_owner,
                           lease_expires_at,
                           created_at,
                           updated_at,
                           completed_at
                    FROM agent_runs
                    """ + whereClause,
                    parameters,
                    this::mapRun);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private int updateRow(AgentRun run, long expectedVersion, String requiredLeaseOwner) {
        String leasePredicate = requiredLeaseOwner == null ? "" : " AND lease_owner = :requiredLeaseOwner";
        MapSqlParameterSource parameters = parameters(run)
                .addValue("expectedVersion", expectedVersion)
                .addValue("requiredLeaseOwner", requiredLeaseOwner);
        return jdbcTemplate.update("""
                UPDATE agent_runs
                SET status = :status,
                    current_step = :currentStep,
                    max_steps = :maxSteps,
                    version = :version,
                    checkpoint = CAST(:checkpoint AS JSON),
                    pending_approval_id = :pendingApprovalId,
                    final_answer = :finalAnswer,
                    error_message = :errorMessage,
                    lease_owner = :leaseOwner,
                    lease_expires_at = :leaseExpiresAt,
                    updated_at = :updatedAt,
                    completed_at = :completedAt
                WHERE id = :id
                  AND version = :expectedVersion
                """ + leasePredicate,
                parameters);
    }

    private void insertEvent(AgentRunEvent event) {
        jdbcTemplate.update("""
                INSERT INTO agent_run_events (id, run_id, sequence, event_type, payload, created_at)
                VALUES (:id, :runId, :sequence, :eventType, CAST(:payload AS JSON), :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("runId", uuid(event.runId()))
                        .addValue("sequence", event.sequence())
                        .addValue("eventType", event.type().name())
                        .addValue("payload", event.payloadJson())
                        .addValue("createdAt", timestamp(event.createdAt())));
    }

    private AgentRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRun(
                rs.getString("id"),
                rs.getString("owner_key"),
                rs.getString("session_id"),
                rs.getString("conversation_id"),
                rs.getString("trace_id"),
                rs.getString("user_message"),
                AgentRunStatus.valueOf(rs.getString("status")),
                rs.getInt("current_step"),
                rs.getInt("max_steps"),
                rs.getLong("version"),
                rs.getLong("last_event_sequence"),
                rs.getString("checkpoint"),
                rs.getString("pending_approval_id"),
                rs.getString("final_answer"),
                rs.getString("error_message"),
                rs.getString("lease_owner"),
                instant(rs.getTimestamp("lease_expires_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at"))
        );
    }

    private AgentRunEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRunEvent(
                rs.getString("run_id"),
                rs.getLong("sequence"),
                AgentRunEventType.valueOf(rs.getString("event_type")),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private MapSqlParameterSource parameters(AgentRun run) {
        return new MapSqlParameterSource()
                .addValue("id", uuid(run.runId()))
                .addValue("ownerKey", run.ownerKey())
                .addValue("sessionId", run.sessionId())
                .addValue("conversationId", run.conversationId())
                .addValue("traceId", run.traceId())
                .addValue("userMessage", run.userMessage())
                .addValue("status", run.status().name())
                .addValue("currentStep", run.currentStep())
                .addValue("maxSteps", run.maxSteps())
                .addValue("version", run.version())
                .addValue("lastEventSequence", run.lastEventSequence())
                .addValue("checkpoint", run.checkpointJson())
                .addValue("pendingApprovalId", nullableUuid(run.pendingApprovalId()))
                .addValue("finalAnswer", run.finalAnswer())
                .addValue("errorMessage", run.errorMessage())
                .addValue("leaseOwner", run.leaseOwner())
                .addValue("leaseExpiresAt", timestamp(run.leaseExpiresAt()))
                .addValue("createdAt", timestamp(run.createdAt()))
                .addValue("updatedAt", timestamp(run.updatedAt()))
                .addValue("completedAt", timestamp(run.completedAt()));
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private UUID nullableUuid(String value) {
        return value == null ? null : uuid(value);
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void requireLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("agent run lease duration must be positive");
        }
    }
}
