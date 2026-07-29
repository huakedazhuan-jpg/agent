package com.hkdzagent.agent.memory;

import com.hkdzagent.agent.security.ActorIdentity;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility adapter for local JSONL memory. Production JDBC mode uses
 * {@link JdbcConversationMessageRepository}.
 */
public class ChatMemoryConversationMessageRepository
        implements ConversationMessageRepository {

    private final ChatMemory chatMemory;
    private final Map<String, StoredConversationMessage> runMessages = new ConcurrentHashMap<>();

    public ChatMemoryConversationMessageRepository(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    @Override
    public StoredConversationMessage saveRunMessage(
            String ownerKey, String externalConversationId, String runId,
            String role, String content, int tokenCount, String messageType, String source
    ) {
        String key = runId + ":" + messageType;
        return runMessages.computeIfAbsent(key, ignored -> {
            String encoded = encoded(ownerKey, externalConversationId);
            Message message = "USER".equals(role)
                    ? new UserMessage(content == null ? "" : content)
                    : new AssistantMessage(content == null ? "" : content);
            chatMemory.add(encoded, List.of(message));
            long index = Math.max(0, chatMemory.get(encoded).size() - 1L);
            return stored(ownerKey, externalConversationId, runId, role, content,
                    tokenCount, messageType, source, index);
        });
    }

    @Override
    public List<StoredConversationMessage> findAfter(
            String ownerKey, String externalConversationId, long afterIndex
    ) {
        String encoded = encoded(ownerKey, externalConversationId);
        List<Message> history = chatMemory.get(encoded);
        List<StoredConversationMessage> result = new ArrayList<>();
        UUID expectedConversationId = conversationUuid(ownerKey, externalConversationId);
        for (int i = 0; i < history.size(); i++) {
            if (i <= afterIndex) continue;
            Message message = history.get(i);
            String role = message.getMessageType().name();
            int index = i;
            StoredConversationMessage known = runMessages.values().stream()
                    .filter(candidate -> candidate.conversationId().equals(expectedConversationId)
                            && candidate.messageIndex() == index
                            && candidate.role().equals(role))
                    .findFirst().orElse(null);
            result.add(known == null
                    ? stored(ownerKey, externalConversationId, null, role,
                            message.getText(), 0, "CHAT", "LEGACY", i)
                    : known);
        }
        return result;
    }

    @Override
    public void clear(String ownerKey, String externalConversationId) {
        chatMemory.clear(encoded(ownerKey, externalConversationId));
        UUID target = conversationUuid(ownerKey, externalConversationId);
        runMessages.entrySet().removeIf(entry ->
                entry.getValue().conversationId().equals(target));
    }

    @Override
    public List<StoredConversationMessage> findByConversationId(UUID conversationId) {
        return runMessages.values().stream()
                .filter(message -> message.conversationId().equals(conversationId))
                .sorted(java.util.Comparator.comparingLong(StoredConversationMessage::messageIndex))
                .toList();
    }

    @Override
    public List<StoredConversationMessage> findByRun(String runId) {
        return runMessages.values().stream()
                .filter(message -> runId.equals(message.runId()))
                .sorted(java.util.Comparator.comparingLong(StoredConversationMessage::messageIndex))
                .toList();
    }

    private StoredConversationMessage stored(
            String ownerKey, String externalConversationId, String runId,
            String role, String content, int tokenCount, String type,
            String source, long index
    ) {
        UUID conversationUuid = conversationUuid(ownerKey, externalConversationId);
        UUID messageUuid = UUID.nameUUIDFromBytes(
                (conversationUuid + "\0" + index).getBytes(StandardCharsets.UTF_8));
        return new StoredConversationMessage(
                messageUuid, conversationUuid, ownerKey, runId, index, role,
                content == null ? "" : content, Math.max(0, tokenCount),
                type, source, Instant.now());
    }

    private String encoded(String ownerKey, String externalConversationId) {
        return new OwnedConversationId(
                new ActorIdentity(ownerKey), externalConversationId).encode();
    }

    private UUID conversationUuid(String ownerKey, String externalConversationId) {
        return UUID.nameUUIDFromBytes(
                (ownerKey + "\0" + externalConversationId).getBytes(StandardCharsets.UTF_8));
    }
}
