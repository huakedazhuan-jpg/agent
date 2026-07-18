package com.hkdzagent.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PersistentChatMemory implements ChatMemory {

    private final Path storageFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<StoredMessage> messages = new ArrayList<>();

    public PersistentChatMemory(Path storageFile) {
        this.storageFile = storageFile;
        loadMessages();
    }

    @Override
    public synchronized void add(String conversationId, List<Message> newMessages) {
        for (Message message : newMessages) {
            StoredMessage storedMessage = StoredMessage.from(conversationId, message);
            messages.add(storedMessage);
            append(storedMessage);
        }
    }

    @Override
    public synchronized List<Message> get(String conversationId, int lastN) {
        List<Message> matched = messages.stream()
                .filter(message -> message.sessionId().equals(conversationId))
                .map(StoredMessage::toMessage)
                .toList();

        int fromIndex = Math.max(0, matched.size() - Math.max(0, lastN));
        return List.copyOf(matched.subList(fromIndex, matched.size()));
    }

    @Override
    public synchronized void clear(String conversationId) {
        messages.removeIf(message -> message.sessionId().equals(conversationId));
        rewriteStorage();
    }

    private void loadMessages() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(storageFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    messages.add(objectMapper.readValue(line, StoredMessage.class));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load chat memory from " + storageFile, e);
        }
    }

    private void append(StoredMessage message) {
        try {
            ensureParentDirectory();
            Files.writeString(
                    storageFile,
                    objectMapper.writeValueAsString(message) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append chat memory to " + storageFile, e);
        }
    }

    private void rewriteStorage() {
        try {
            ensureParentDirectory();
            List<String> lines = new ArrayList<>();
            for (StoredMessage message : messages) {
                lines.add(objectMapper.writeValueAsString(message));
            }
            Files.write(storageFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rewrite chat memory at " + storageFile, e);
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = storageFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private record StoredMessage(String sessionId, String role, String content, String createdAt) {

        private static StoredMessage from(String sessionId, Message message) {
            return new StoredMessage(
                    sessionId,
                    message.getMessageType().name(),
                    message.getContent(),
                    Instant.now().toString()
            );
        }

        private Message toMessage() {
            if (MessageType.USER.name().equals(role)) {
                return new UserMessage(content);
            }
            if (MessageType.ASSISTANT.name().equals(role)) {
                return new AssistantMessage(content);
            }
            return new AssistantMessage(content);
        }
    }
}
