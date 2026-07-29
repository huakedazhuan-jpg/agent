package com.hkdzagent.agent.memory;

import java.util.UUID;

public interface ConversationSummaryRepository {

    ConversationSummary find(UUID conversationId, String ownerKey);

    ConversationSummary createIfAbsent(UUID conversationId, String ownerKey);

    boolean update(
            UUID conversationId,
            String ownerKey,
            long expectedVersion,
            String summary,
            long throughMessageIndex);
}
