package com.hkdzagent.agent.memory;

import java.time.Instant;
import java.util.UUID;

public record MemoryItem(
        UUID id,
        String ownerKey,
        MemoryType memoryType,
        String content,
        String normalizedKey,
        UUID sourceConversationId,
        UUID sourceMessageId,
        double importance,
        double confidence,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        ACTIVE,
        DELETED
    }
}
