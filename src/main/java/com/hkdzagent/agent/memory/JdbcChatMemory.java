package com.hkdzagent.agent.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcChatMemory implements ChatMemory {

    private static final String CHANNEL = "chat_memory";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;

    public JdbcChatMemory(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, TransactionOperations.withoutTransaction());
    }

    public JdbcChatMemory(NamedParameterJdbcTemplate jdbcTemplate, TransactionOperations transactionOperations) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionOperations = transactionOperations == null
                ? TransactionOperations.withoutTransaction()
                : transactionOperations;
    }

    @Override
    public void add(String conversationId, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }
        OwnedConversationId identity = conversationIdentity(conversationId);
        transactionOperations.executeWithoutResult(status -> addInTransaction(identity, newMessages));
    }

    @Override
    public List<Message> get(String conversationId) {
        OwnedConversationId identity = conversationIdentity(conversationId);
        UUID databaseConversationId = findConversationId(identity);
        if (databaseConversationId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT role, content
                FROM agent_messages
                WHERE conversation_id = :conversationId
                ORDER BY message_index ASC, created_at ASC, id ASC
                """,
                new MapSqlParameterSource("conversationId", databaseConversationId),
                (rs, rowNum) -> toMessage(rs.getString("role"), rs.getString("content")));
    }

    @Override
    public void clear(String conversationId) {
        OwnedConversationId identity = conversationIdentity(conversationId);
        transactionOperations.executeWithoutResult(status -> clearInTransaction(identity));
    }

    private void addInTransaction(OwnedConversationId identity, List<Message> newMessages) {
        UUID databaseConversationId = findOrCreateConversation(identity);
        long nextIndex = reserveMessageIndexes(databaseConversationId, newMessages.size());
        Instant now = Instant.now();
        for (Message message : newMessages) {
            jdbcTemplate.update("""
                    INSERT INTO agent_messages (
                        id,
                        conversation_id,
                        role,
                        content,
                        message_index,
                        created_at
                    )
                    VALUES (
                        :id,
                        :conversationId,
                        :role,
                        :content,
                        :messageIndex,
                        :createdAt
                    )
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("conversationId", databaseConversationId)
                            .addValue("role", message.getMessageType().name())
                            .addValue("content", message.getText() == null ? "" : message.getText())
                            .addValue("messageIndex", nextIndex++)
                            .addValue("createdAt", Timestamp.from(now)));
        }
        touchConversation(databaseConversationId);
    }

    private void clearInTransaction(OwnedConversationId identity) {
        UUID databaseConversationId = findConversationId(identity);
        if (databaseConversationId == null) {
            return;
        }
        jdbcTemplate.update("""
                DELETE FROM agent_messages
                WHERE conversation_id = :conversationId
                """,
                new MapSqlParameterSource("conversationId", databaseConversationId));
        touchConversation(databaseConversationId);
    }

    private UUID findOrCreateConversation(OwnedConversationId identity) {
        UUID existingConversationId = findConversationId(identity);
        if (existingConversationId != null) {
            return existingConversationId;
        }

        UUID databaseConversationId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO agent_conversations (
                    id,
                    owner_key,
                    channel,
                    external_conversation_id,
                    title,
                    created_at,
                    updated_at
                )
                VALUES (
                    :id,
                    :ownerKey,
                    :channel,
                    :externalConversationId,
                    :title,
                    :createdAt,
                    :updatedAt
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", databaseConversationId)
                        .addValue("ownerKey", identity.owner().key())
                        .addValue("channel", CHANNEL)
                        .addValue("externalConversationId", identity.externalId())
                        .addValue("title", title(identity.externalId()))
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return databaseConversationId;
    }

    private UUID findConversationId(OwnedConversationId identity) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM agent_conversations
                    WHERE owner_key = :ownerKey
                      AND channel = :channel
                      AND external_conversation_id = :externalConversationId
                    """,
                    new MapSqlParameterSource()
                            .addValue("ownerKey", identity.owner().key())
                            .addValue("channel", CHANNEL)
                            .addValue("externalConversationId", identity.externalId()),
                    UUID.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private long reserveMessageIndexes(UUID conversationId, int count) {
        try {
            Long next = jdbcTemplate.queryForObject("""
                    UPDATE agent_conversations
                    SET next_message_index = next_message_index + :count
                    WHERE id = :conversationId
                    RETURNING next_message_index
                    """, new MapSqlParameterSource()
                            .addValue("conversationId", conversationId)
                            .addValue("count", count), Long.class);
            if (next == null) {
                throw new IllegalStateException("conversation disappeared while reserving message indexes");
            }
            return next - count;
        } catch (org.springframework.jdbc.BadSqlGrammarException legacySchema) {
            // Compatibility for pre-V16 test fixtures only. Production schemas use the
            // atomic UPDATE ... RETURNING path above.
            Long maxIndex = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(message_index), -1)
                    FROM agent_messages
                    WHERE conversation_id = :conversationId
                    """, new MapSqlParameterSource("conversationId", conversationId), Long.class);
            return maxIndex == null ? 0 : maxIndex + 1;
        }
    }

    private void touchConversation(UUID conversationId) {
        jdbcTemplate.update("""
                UPDATE agent_conversations
                SET updated_at = :updatedAt
                WHERE id = :conversationId
                """,
                new MapSqlParameterSource()
                        .addValue("conversationId", conversationId)
                        .addValue("updatedAt", Timestamp.from(Instant.now())));
    }

    private Message toMessage(String role, String content) {
        if (MessageType.USER.name().equals(role)) {
            return new UserMessage(content);
        }
        if (MessageType.SYSTEM.name().equals(role)) {
            return new SystemMessage(content);
        }
        if (MessageType.ASSISTANT.name().equals(role)) {
            return new AssistantMessage(content);
        }
        return new AssistantMessage(content);
    }

    private OwnedConversationId conversationIdentity(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        return OwnedConversationId.decodeOrLegacy(conversationId);
    }

    private String title(String conversationId) {
        return conversationId.length() <= 256 ? conversationId : conversationId.substring(0, 256);
    }
}
