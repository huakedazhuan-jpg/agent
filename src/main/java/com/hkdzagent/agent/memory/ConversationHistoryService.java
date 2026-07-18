package com.hkdzagent.agent.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public class ConversationHistoryService {

    private final ChatMemory chatMemory;

    public ConversationHistoryService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public List<Message> findBySessionId(String sessionId, int limit) {
        List<Message> messages = chatMemory.get(sessionId);
        int safeLimit = Math.max(0, limit);
        int fromIndex = Math.max(0, messages.size() - safeLimit);
        return List.copyOf(messages.subList(fromIndex, messages.size()));
    }
}
