package com.hkdzagent.agent.memory;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcConversationMessageRepository implements ConversationMessageRepository {

    private static final String CHANNEL = "chat_memory";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JdbcConversationMessageRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    JdbcConversationMessageRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public StoredConversationMessage saveRunMessage(
            String ownerKey,
            String externalConversationId,
            String runId,
            String role,
            String content,
            int tokenCount,
            String messageType,
            String source
    ) {
        return transactions.execute(status -> {
            UUID conversationId = findOrCreateConversation(ownerKey, externalConversationId);
            StoredConversationMessage existing = findRunMessage(runId, messageType);
            if (existing != null) {
                return existing;
            }
            long index = reserveIndexes(conversationId, 1);
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                        INSERT INTO agent_messages (
                            id, conversation_id, role, content, message_index,
                            run_id, token_count, message_type, source, created_at
                        ) VALUES (
                            :id, :conversationId, :role, :content, :messageIndex,
                            :runId, :tokenCount, :messageType, :source, :createdAt
                        )
                        ON CONFLICT (run_id, message_type)
                        WHERE run_id IS NOT NULL AND message_type IN ('USER', 'ASSISTANT')
                        DO NOTHING
                        RETURNING id
                        """, new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("conversationId", conversationId)
                        .addValue("role", role)
                        .addValue("content", content == null ? "" : content)
                        .addValue("messageIndex", index)
                        .addValue("runId", UUID.fromString(runId))
                        .addValue("tokenCount", Math.max(0, tokenCount))
                        .addValue("messageType", messageType)
                        .addValue("source", source)
                        .addValue("createdAt", Timestamp.from(now)),
                    (rs, row) -> rs.getObject("id", UUID.class));
            if (inserted.isEmpty()) {
                StoredConversationMessage raced = findRunMessage(runId, messageType);
                if (raced != null) {
                    return raced;
                }
                throw new IllegalStateException("run message conflict did not expose existing row");
            }
            touch(conversationId, now);
            return new StoredConversationMessage(
                    id, conversationId, ownerKey, runId, index, role,
                    content == null ? "" : content, Math.max(0, tokenCount),
                    messageType, source, now);
        });
    }

    @Override
    public List<StoredConversationMessage> findAfter(
            String ownerKey, String externalConversationId, long afterIndex
    ) {
        return jdbc.query("""
                SELECT message.id, message.conversation_id, conversation.owner_key,
                       message.run_id, message.message_index, message.role, message.content,
                       message.token_count, message.message_type, message.source, message.created_at
                FROM agent_messages message
                JOIN agent_conversations conversation ON conversation.id = message.conversation_id
                WHERE conversation.owner_key = :ownerKey
                  AND conversation.channel = :channel
                  AND conversation.external_conversation_id = :externalConversationId
                  AND message.message_index > :afterIndex
                ORDER BY message.message_index ASC
                """, new MapSqlParameterSource()
                        .addValue("ownerKey", ownerKey)
                        .addValue("channel", CHANNEL)
                        .addValue("externalConversationId", externalConversationId)
                        .addValue("afterIndex", afterIndex),
                (rs, row) -> new StoredConversationMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("owner_key"),
                        rs.getObject("run_id") == null ? null : rs.getObject("run_id", UUID.class).toString(),
                        rs.getLong("message_index"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getString("message_type"),
                        rs.getString("source"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public void clear(String ownerKey, String externalConversationId) {
        transactions.executeWithoutResult(status -> {
            UUID id = findConversation(ownerKey, externalConversationId);
            if (id == null) {
                return;
            }
            jdbc.update("DELETE FROM agent_messages WHERE conversation_id = :id",
                    new MapSqlParameterSource("id", id));
            jdbc.update("""
                    UPDATE agent_conversations
                    SET next_message_index = 0, updated_at = :updatedAt
                    WHERE id = :id
                    """, new MapSqlParameterSource("id", id)
                    .addValue("updatedAt", Timestamp.from(clock.instant())));
        });
    }

    @Override
    public List<StoredConversationMessage> findByConversationId(UUID conversationId) {
        return queryMessages("""
                WHERE message.conversation_id = :conversationId
                ORDER BY message.message_index ASC
                """, new MapSqlParameterSource("conversationId", conversationId));
    }

    @Override
    public List<StoredConversationMessage> findByRun(String runId) {
        return queryMessages("""
                WHERE message.run_id = :runId
                ORDER BY message.message_index ASC
                """, new MapSqlParameterSource("runId", UUID.fromString(runId)));
    }

    public long reserveIndexes(UUID conversationId, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        Long next = jdbc.queryForObject("""
                UPDATE agent_conversations
                SET next_message_index = next_message_index + :count
                WHERE id = :conversationId
                RETURNING next_message_index
                """, new MapSqlParameterSource()
                        .addValue("count", count)
                        .addValue("conversationId", conversationId), Long.class);
        if (next == null) {
            throw new IllegalStateException("conversation disappeared while reserving message indexes");
        }
        return next - count;
    }

    public UUID findOrCreateConversation(String ownerKey, String externalConversationId) {
        UUID existing = findConversation(ownerKey, externalConversationId);
        if (existing != null) {
            return existing;
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO agent_conversations (
                    id, owner_key, channel, external_conversation_id, title,
                    next_message_index, created_at, updated_at
                ) VALUES (
                    :id, :ownerKey, :channel, :externalConversationId, :title,
                    0, :createdAt, :updatedAt
                )
                ON CONFLICT (owner_key, channel, external_conversation_id)
                WHERE external_conversation_id IS NOT NULL DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("ownerKey", ownerKey)
                        .addValue("channel", CHANNEL)
                        .addValue("externalConversationId", externalConversationId)
                        .addValue("title", truncate(externalConversationId, 256))
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        UUID created = findConversation(ownerKey, externalConversationId);
        if (created == null) {
            throw new IllegalStateException("failed to create conversation");
        }
        return created;
    }

    private UUID findConversation(String ownerKey, String externalConversationId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id FROM agent_conversations
                    WHERE owner_key = :ownerKey AND channel = :channel
                      AND external_conversation_id = :externalConversationId
                    """, new MapSqlParameterSource()
                            .addValue("ownerKey", ownerKey)
                            .addValue("channel", CHANNEL)
                            .addValue("externalConversationId", externalConversationId), UUID.class);
        } catch (EmptyResultDataAccessException missing) {
            return null;
        }
    }

    private StoredConversationMessage findRunMessage(String runId, String messageType) {
        List<StoredConversationMessage> found = queryMessages("""
                WHERE message.run_id = :runId AND message.message_type = :messageType
                """, new MapSqlParameterSource()
                        .addValue("runId", UUID.fromString(runId))
                        .addValue("messageType", messageType));
        return found.isEmpty() ? null : found.get(0);
    }

    private List<StoredConversationMessage> queryMessages(
            String whereAndOrder, MapSqlParameterSource parameters
    ) {
        return jdbc.query("""
                SELECT message.id, message.conversation_id, conversation.owner_key,
                       message.run_id, message.message_index, message.role, message.content,
                       message.token_count, message.message_type, message.source, message.created_at
                FROM agent_messages message
                JOIN agent_conversations conversation ON conversation.id = message.conversation_id
                """ + whereAndOrder, parameters,
                (rs, row) -> new StoredConversationMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("owner_key"),
                        rs.getObject("run_id", UUID.class).toString(),
                        rs.getLong("message_index"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getString("message_type"),
                        rs.getString("source"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private void touch(UUID conversationId, Instant now) {
        jdbc.update("UPDATE agent_conversations SET updated_at = :now WHERE id = :id",
                new MapSqlParameterSource("id", conversationId)
                        .addValue("now", Timestamp.from(now)));
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
