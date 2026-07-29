package com.hkdzagent.agent.memory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcMemoryRepository implements MemoryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcMemoryRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcMemoryRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public MemoryItem upsert(MemoryItem item) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO agent_memory_items (
                    id, owner_key, memory_type, content, normalized_key,
                    source_conversation_id, source_message_id, importance,
                    confidence, status, created_at, updated_at
                ) VALUES (
                    :id, :ownerKey, :memoryType, :content, :normalizedKey,
                    :sourceConversationId, :sourceMessageId, :importance,
                    :confidence, 'ACTIVE', :createdAt, :updatedAt
                )
                ON CONFLICT (owner_key, memory_type, normalized_key)
                DO UPDATE SET
                    content = EXCLUDED.content,
                    source_conversation_id = COALESCE(EXCLUDED.source_conversation_id,
                                                      agent_memory_items.source_conversation_id),
                    source_message_id = COALESCE(EXCLUDED.source_message_id,
                                                 agent_memory_items.source_message_id),
                    importance = GREATEST(agent_memory_items.importance, EXCLUDED.importance),
                    confidence = GREATEST(agent_memory_items.confidence, EXCLUDED.confidence),
                    status = 'ACTIVE',
                    updated_at = EXCLUDED.updated_at
                """, parameters(item, now));
        return searchByKey(item.ownerKey(), item.memoryType(), item.normalizedKey());
    }

    @Override
    public List<MemoryItem> findActive(String ownerKey, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_memory_items
                WHERE owner_key = :ownerKey AND status = 'ACTIVE'
                ORDER BY importance DESC, confidence DESC, updated_at DESC, id
                LIMIT :limit
                """, new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("limit", safeLimit(limit)), this::map);
    }

    @Override
    public List<MemoryItem> search(String ownerKey, String normalizedQuery, int limit) {
        return jdbc.query("""
                SELECT *,
                       CASE WHEN normalized_key = :query THEN 1.0
                            ELSE similarity(normalized_key, :query) END AS relevance
                FROM agent_memory_items
                WHERE owner_key = :ownerKey
                  AND status = 'ACTIVE'
                  AND (normalized_key = :query
                       OR normalized_key % :query
                       OR normalized_key ILIKE '%' || :query || '%'
                       OR :query ILIKE '%' || normalized_key || '%')
                ORDER BY relevance DESC, importance DESC, confidence DESC, updated_at DESC, id
                LIMIT :limit
                """, new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("query", normalizedQuery)
                        .addValue("limit", safeLimit(limit)), this::map);
    }

    @Override
    public boolean delete(String ownerKey, UUID memoryId) {
        return jdbc.update("""
                DELETE FROM agent_memory_items
                WHERE id = :id AND owner_key = :ownerKey
                """, new MapSqlParameterSource()
                        .addValue("id", memoryId)
                        .addValue("ownerKey", ownerKey)) == 1;
    }

    @Override
    public int deleteAll(String ownerKey) {
        return jdbc.update("DELETE FROM agent_memory_items WHERE owner_key = :ownerKey",
                new MapSqlParameterSource("ownerKey", ownerKey));
    }

    private MemoryItem searchByKey(String ownerKey, MemoryType type, String key) {
        List<MemoryItem> rows = jdbc.query("""
                SELECT * FROM agent_memory_items
                WHERE owner_key = :ownerKey AND memory_type = :memoryType
                  AND normalized_key = :normalizedKey
                """, new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("memoryType", type.name())
                        .addValue("normalizedKey", key), this::map);
        if (rows.isEmpty()) {
            throw new IllegalStateException("memory upsert did not return a row");
        }
        return rows.get(0);
    }

    private MemoryItem map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new MemoryItem(
                rs.getObject("id", UUID.class),
                rs.getString("owner_key"),
                MemoryType.valueOf(rs.getString("memory_type")),
                rs.getString("content"),
                rs.getString("normalized_key"),
                rs.getObject("source_conversation_id", UUID.class),
                rs.getObject("source_message_id", UUID.class),
                rs.getDouble("importance"),
                rs.getDouble("confidence"),
                MemoryItem.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private MapSqlParameterSource parameters(MemoryItem item, Instant now) {
        return new MapSqlParameterSource()
                .addValue("id", item.id())
                .addValue("ownerKey", item.ownerKey())
                .addValue("memoryType", item.memoryType().name())
                .addValue("content", item.content())
                .addValue("normalizedKey", item.normalizedKey())
                .addValue("sourceConversationId", item.sourceConversationId())
                .addValue("sourceMessageId", item.sourceMessageId())
                .addValue("importance", clamp(item.importance()))
                .addValue("confidence", clamp(item.confidence()))
                .addValue("createdAt", Timestamp.from(item.createdAt() == null ? now : item.createdAt()))
                .addValue("updatedAt", Timestamp.from(now));
    }

    private int safeLimit(int limit) {
        return Math.min(100, Math.max(1, limit));
    }

    private double clamp(double value) {
        return Math.min(1, Math.max(0, value));
    }
}
