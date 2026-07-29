package com.hkdzagent.agent.memory;

import com.hkdzagent.agent.context.TokenCounter;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class ConversationSummarizer {

    private final ConversationSummaryRepository summaries;
    private final TokenCounter tokenCounter;
    private final Function<String, String> model;
    private final int triggerTokens;

    public ConversationSummarizer(
            ConversationSummaryRepository summaries,
            TokenCounter tokenCounter,
            Function<String, String> model,
            int triggerTokens
    ) {
        this.summaries = summaries;
        this.tokenCounter = tokenCounter;
        this.model = Objects.requireNonNull(model, "model");
        this.triggerTokens = triggerTokens;
    }

    public boolean summarize(
            String ownerKey,
            java.util.UUID conversationId,
            List<StoredConversationMessage> allMessages
    ) {
        try {
            return summarizeOrThrow(ownerKey, conversationId, allMessages);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public boolean summarizeOrThrow(
            String ownerKey,
            java.util.UUID conversationId,
            List<StoredConversationMessage> allMessages
    ) {
        ConversationSummary current = summaries.createIfAbsent(conversationId, ownerKey);
        List<StoredConversationMessage> pending = allMessages.stream()
                .filter(message -> message.messageIndex() > current.throughMessageIndex())
                .toList();
        int tokens = pending.stream().mapToInt(message ->
                message.tokenCount() > 0
                        ? message.tokenCount()
                        : tokenCounter.count(message.content())).sum();
        if (pending.isEmpty() || tokens < triggerTokens) {
            return false;
        }
        String next = model.apply(SummaryPrompt.render(current.summary(), pending));
        if (next == null || next.isBlank()) {
            return false;
        }
        long through = pending.get(pending.size() - 1).messageIndex();
        return summaries.update(
                conversationId, ownerKey, current.version(), next.strip(), through);
    }
}
