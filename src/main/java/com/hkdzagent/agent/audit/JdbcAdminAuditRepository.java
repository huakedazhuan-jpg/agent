package com.hkdzagent.agent.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

public class JdbcAdminAuditRepository implements AdminAuditRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAdminAuditRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AdminAuditEvent save(AdminAuditEvent event) {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_events (
                    id, actor_key, action, resource_type,
                    resource_id, outcome, detail, created_at
                ) VALUES (
                    CAST(:id AS UUID), :actorKey, :action, :resourceType,
                    :resourceId, :outcome, :detail, :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("actorKey", event.actorKey())
                .addValue("action", event.action())
                .addValue("resourceType", event.resourceType())
                .addValue("resourceId", event.resourceId())
                .addValue("outcome", event.outcome().name())
                .addValue("detail", event.detail())
                .addValue("createdAt", Timestamp.from(event.createdAt())));
        return event;
    }

    @Override
    public List<AdminAuditEvent> findRecent(int limit) {
        return jdbcTemplate.query("""
                SELECT CAST(id AS VARCHAR) AS id, actor_key, action,
                       resource_type, resource_id, outcome, detail, created_at
                FROM admin_audit_events
                ORDER BY created_at DESC, id ASC
                LIMIT :limit
                """, new MapSqlParameterSource("limit", Math.max(1, limit)), this::mapEvent);
    }

    private AdminAuditEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AdminAuditEvent(
                rs.getString("id"), rs.getString("actor_key"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"),
                AdminAuditEvent.Outcome.valueOf(rs.getString("outcome")),
                rs.getString("detail"), rs.getTimestamp("created_at").toInstant());
    }
}
