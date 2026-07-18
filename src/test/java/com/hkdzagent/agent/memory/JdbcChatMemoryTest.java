package com.hkdzagent.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcChatMemoryTest {

    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private ChatMemory chatMemory;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:chat_memory_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE agent_conversations (
                    id UUID PRIMARY KEY,
                    channel VARCHAR(32) NOT NULL,
                    external_conversation_id VARCHAR(256),
                    title VARCHAR(256),
                    metadata VARCHAR NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE agent_messages (
                    id UUID PRIMARY KEY,
                    conversation_id UUID NOT NULL REFERENCES agent_conversations (id) ON DELETE CASCADE,
                    role VARCHAR(32) NOT NULL,
                    content CLOB NOT NULL,
                    message_index INTEGER NOT NULL DEFAULT 0,
                    metadata VARCHAR NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
        chatMemory = new JdbcChatMemory(namedParameterJdbcTemplate);
    }

    @Test
    void storesSessionScopedHistoryInDatabaseOrder() {
        chatMemory.add("session-a", List.of(
                new SystemMessage("system instruction"),
                new UserMessage("first question"),
                new AssistantMessage("first answer")
        ));
        chatMemory.add("session-b", List.of(
                new UserMessage("other question"),
                new AssistantMessage("other answer")
        ));
        chatMemory.add("session-a", List.of(new UserMessage("follow up")));

        List<Message> sessionAHistory = chatMemory.get("session-a");
        List<Message> sessionBHistory = chatMemory.get("session-b");

        assertThat(sessionAHistory)
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(sessionAHistory)
                .extracting(Message::getText)
                .containsExactly("system instruction", "first question", "first answer", "follow up");
        assertThat(sessionBHistory)
                .extracting(Message::getText)
                .containsExactly("other question", "other answer");
    }

    @Test
    void reloadsMessagesFromDatabaseAndClearsBySession() {
        chatMemory.add("session-persisted", List.of(
                new UserMessage("persisted question"),
                new AssistantMessage("persisted answer")
        ));

        ChatMemory reloadedMemory = new JdbcChatMemory(namedParameterJdbcTemplate);
        assertThat(reloadedMemory.get("session-persisted"))
                .extracting(Message::getText)
                .containsExactly("persisted question", "persisted answer");

        reloadedMemory.clear("session-persisted");

        assertThat(chatMemory.get("session-persisted")).isEmpty();
    }

    @Test
    void emptyAndMissingSessionsAreNoOps() {
        chatMemory.add("session-empty", List.of());
        chatMemory.clear("missing-session");

        assertThat(chatMemory.get("session-empty")).isEmpty();
        assertThat(chatMemory.get("missing-session")).isEmpty();
    }
}
