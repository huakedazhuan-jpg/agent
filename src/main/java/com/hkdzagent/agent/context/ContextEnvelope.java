package com.hkdzagent.agent.context;

import java.util.List;
import java.util.Map;

public record ContextEnvelope(
        List<ContextSection> messages,
        int totalTokens,
        Map<ContextSection.Kind, Integer> sectionTokens,
        List<ContextChange> changes
) {
    public ContextEnvelope {
        messages = List.copyOf(messages);
        sectionTokens = Map.copyOf(sectionTokens);
        changes = List.copyOf(changes);
    }

    public record ContextChange(String sectionId, Action action, String reason, int removedTokens) {
        public enum Action {
            COMPRESSED,
            REMOVED,
            TRUNCATED
        }
    }
}
