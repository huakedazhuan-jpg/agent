package com.hkdzagent.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class PersistentChatMemoryTest {

    private static final String MEMORY_PACKAGE = "com.hkdzagent.agent.memory.";

    @TempDir
    Path tempDir;

    @Test
    void storesSessionScopedHistoryOnDiskAndReloadsIt() throws Exception {
        Path storageFile = tempDir.resolve("chat-memory.jsonl");
        ChatMemory firstMemory = newPersistentMemory(storageFile);

        firstMemory.add("session-a", List.of(
                new UserMessage("first question"),
                new AssistantMessage("first answer")
        ));
        firstMemory.add("session-b", List.of(
                new UserMessage("other question"),
                new AssistantMessage("other answer")
        ));

        assertThat(Files.exists(storageFile)).isTrue();
        assertThat(Files.readString(storageFile))
                .contains("session-a", "first question", "first answer", "session-b")
                .doesNotContain("missing-session");

        ChatMemory reloadedMemory = newPersistentMemory(storageFile);
        Object historyService = newHistoryService(reloadedMemory);

        List<Message> sessionAHistory = findBySessionId(historyService, "session-a", 10);
        assertThat(messageTypes(sessionAHistory)).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(messageContents(sessionAHistory)).containsExactly("first question", "first answer");

        List<Message> sessionBHistory = findBySessionId(historyService, "session-b", 10);
        assertThat(messageContents(sessionBHistory)).containsExactly("other question", "other answer");

        List<Message> limitedHistory = findBySessionId(historyService, "session-a", 1);
        assertThat(messageContents(limitedHistory)).containsExactly("first answer");
    }

    private static ChatMemory newPersistentMemory(Path storageFile) throws Exception {
        Class<?> memoryClass = load("PersistentChatMemory");
        assertThat(ChatMemory.class.isAssignableFrom(memoryClass))
                .as(memoryClass.getName() + " should keep compatibility with Spring AI ChatMemory")
                .isTrue();
        Constructor<?> constructor = memoryClass.getConstructor(Path.class);
        return (ChatMemory) constructor.newInstance(storageFile);
    }

    private static Object newHistoryService(ChatMemory chatMemory) throws Exception {
        Class<?> serviceClass = load("ConversationHistoryService");
        Constructor<?> constructor = serviceClass.getConstructor(ChatMemory.class);
        return constructor.newInstance(chatMemory);
    }

    @SuppressWarnings("unchecked")
    private static List<Message> findBySessionId(Object service, String sessionId, int limit) throws Exception {
        Method method = service.getClass().getMethod("findBySessionId", String.class, int.class);
        return (List<Message>) method.invoke(service, sessionId, limit);
    }

    private static List<MessageType> messageTypes(List<Message> messages) {
        return messages.stream()
                .map(Message::getMessageType)
                .toList();
    }

    private static List<String> messageContents(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .toList();
    }

    private static Class<?> load(String simpleName) {
        String className = MEMORY_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }
}
