package com.hkdzagent.agent.memory;

import java.time.Instant;
import java.util.UUID;

public record ConversationSummary(
        UUID conversationId,
        String ownerKey,
        String summary,
        long throughMessageIndex,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
