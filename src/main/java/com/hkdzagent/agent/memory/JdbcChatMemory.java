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
    public synchronized void add(String conversationId, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }
        String requiredConversationId = requireConversationId(conversationId);
        transactionOperations.executeWithoutResult(status -> addInTransaction(requiredConversationId, newMessages));
    }

    @Override
    public synchronized List<Message> get(String conversationId) {
        String requiredConversationId = requireConversationId(conversationId);
        UUID databaseConversationId = findConversationId(requiredConversationId);
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
    public synchronized void clear(String conversationId) {
        String requiredConversationId = requireConversationId(conversationId);
        transactionOperations.executeWithoutResult(status -> clearInTransaction(requiredConversationId));
    }

    private void addInTransaction(String conversationId, List<Message> newMessages) {
        UUID databaseConversationId = findOrCreateConversation(conversationId);
        int nextIndex = nextMessageIndex(databaseConversationId);
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

    private void clearInTransaction(String conversationId) {
        UUID databaseConversationId = findConversationId(conversationId);
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

    private UUID findOrCreateConversation(String conversationId) {
        UUID existingConversationId = findConversationId(conversationId);
        if (existingConversationId != null) {
            return existingConversationId;
        }

        UUID databaseConversationId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO agent_conversations (
                    id,
                    channel,
                    external_conversation_id,
                    title,
                    created_at,
                    updated_at
                )
                VALUES (
                    :id,
                    :channel,
                    :externalConversationId,
                    :title,
                    :createdAt,
                    :updatedAt
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", databaseConversationId)
                        .addValue("channel", CHANNEL)
                        .addValue("externalConversationId", conversationId)
                        .addValue("title", title(conversationId))
                        .addValue("createdAt", Timestamp.from(now))
                        .addValue("updatedAt", Timestamp.from(now)));
        return databaseConversationId;
    }

    private UUID findConversationId(String conversationId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id
                    FROM agent_conversations
                    WHERE channel = :channel
                      AND external_conversation_id = :externalConversationId
                    """,
                    new MapSqlParameterSource()
                            .addValue("channel", CHANNEL)
                            .addValue("externalConversationId", conversationId),
                    UUID.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private int nextMessageIndex(UUID conversationId) {
        Integer maxIndex = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(message_index), -1)
                FROM agent_messages
                WHERE conversation_id = :conversationId
                """,
                new MapSqlParameterSource("conversationId", conversationId),
                Integer.class);
        return maxIndex == null ? 0 : maxIndex + 1;
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

    private String requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        return conversationId;
    }

    private String title(String conversationId) {
        return conversationId.length() <= 256 ? conversationId : conversationId.substring(0, 256);
    }
}
