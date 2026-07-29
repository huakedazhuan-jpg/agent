package com.hkdzagent.agent.memory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcConversationSummaryRepository implements ConversationSummaryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcConversationSummaryRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcConversationSummaryRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public ConversationSummary find(UUID conversationId, String ownerKey) {
        List<ConversationSummary> rows = jdbc.query("""
                SELECT conversation_id, owner_key, summary, through_message_index,
                       version, created_at, updated_at
                FROM conversation_summaries
                WHERE conversation_id = :conversationId AND owner_key = :ownerKey
                """, params(conversationId, ownerKey),
                (rs, row) -> new ConversationSummary(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("owner_key"),
                        rs.getString("summary"),
                        rs.getLong("through_message_index"),
                        rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public ConversationSummary createIfAbsent(UUID conversationId, String ownerKey) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO conversation_summaries (
                    conversation_id, owner_key, summary, through_message_index,
                    version, created_at, updated_at
                ) VALUES (
                    :conversationId, :ownerKey, '', -1, 0, :now, :now
                ) ON CONFLICT (conversation_id) DO NOTHING
                """, params(conversationId, ownerKey).addValue("now", Timestamp.from(now)));
        return find(conversationId, ownerKey);
    }

    @Override
    public boolean update(
            UUID conversationId,
            String ownerKey,
            long expectedVersion,
            String summary,
            long throughMessageIndex
    ) {
        return jdbc.update("""
                UPDATE conversation_summaries
                SET summary = :summary,
                    through_message_index = :throughMessageIndex,
                    version = version + 1,
                    updated_at = :updatedAt
                WHERE conversation_id = :conversationId
                  AND owner_key = :ownerKey
                  AND version = :expectedVersion
                  AND through_message_index < :throughMessageIndex
                """, params(conversationId, ownerKey)
                .addValue("expectedVersion", expectedVersion)
                .addValue("summary", summary)
                .addValue("throughMessageIndex", throughMessageIndex)
                .addValue("updatedAt", Timestamp.from(clock.instant()))) == 1;
    }

    private MapSqlParameterSource params(UUID conversationId, String ownerKey) {
        return new MapSqlParameterSource()
                .addValue("conversationId", conversationId)
                .addValue("ownerKey", ownerKey);
    }
}
