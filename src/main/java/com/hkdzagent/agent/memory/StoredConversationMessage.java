package com.hkdzagent.agent.memory;

import java.time.Instant;
import java.util.UUID;

public record StoredConversationMessage(
        UUID id,
        UUID conversationId,
        String ownerKey,
        String runId,
        long messageIndex,
        String role,
        String content,
        int tokenCount,
        String messageType,
        String source,
        Instant createdAt
) {
}
