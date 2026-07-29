package com.hkdzagent.agent.memory;

import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository {

    ConversationMessageRepository NOOP = new ConversationMessageRepository() {
        @Override
        public StoredConversationMessage saveRunMessage(
                String ownerKey, String externalConversationId, String runId,
                String role, String content, int tokenCount, String messageType, String source
        ) {
            return null;
        }

        @Override
        public List<StoredConversationMessage> findAfter(
                String ownerKey, String externalConversationId, long afterIndex
        ) {
            return List.of();
        }

        @Override
        public void clear(String ownerKey, String externalConversationId) {
        }

        @Override
        public List<StoredConversationMessage> findByConversationId(UUID conversationId) {
            return List.of();
        }

        @Override
        public List<StoredConversationMessage> findByRun(String runId) {
            return List.of();
        }
    };

    StoredConversationMessage saveRunMessage(
            String ownerKey,
            String externalConversationId,
            String runId,
            String role,
            String content,
            int tokenCount,
            String messageType,
            String source
    );

    List<StoredConversationMessage> findAfter(
            String ownerKey, String externalConversationId, long afterIndex);

    void clear(String ownerKey, String externalConversationId);

    List<StoredConversationMessage> findByConversationId(UUID conversationId);

    List<StoredConversationMessage> findByRun(String runId);
}
