package com.hkdzagent.agent.context;

import com.hkdzagent.agent.memory.ConversationMessageRepository;
import com.hkdzagent.agent.memory.ConversationSummary;
import com.hkdzagent.agent.memory.ConversationSummaryRepository;
import com.hkdzagent.agent.memory.MemoryRetriever;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.memory.StoredConversationMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

public class DefaultContextSource implements ContextSource {

    private final ChatMemory chatMemory;
    private final ConversationMessageRepository messages;
    private final ConversationSummaryRepository summaries;
    private final MemoryRetriever memories;

    public DefaultContextSource(
            ChatMemory chatMemory,
            ConversationMessageRepository messages,
            ConversationSummaryRepository summaries,
            MemoryRetriever memories
    ) {
        this.chatMemory = chatMemory;
        this.messages = messages;
        this.summaries = summaries;
        this.memories = memories;
    }

    @Override
    public List<ContextSection> load(ContextRequest request) {
        List<ContextSection> result = new ArrayList<>();
        OwnedConversationId identity = OwnedConversationId.decodeOrLegacy(request.conversationId());
        if (messages != null) {
            List<StoredConversationMessage> stored =
                    messages.findAfter(request.ownerKey(), identity.externalId(), -1);
            if (!stored.isEmpty() && summaries != null) {
                ConversationSummary summary =
                        summaries.find(stored.get(0).conversationId(), request.ownerKey());
                if (summary != null && !summary.summary().isBlank()) {
                    result.add(new ContextSection(
                            "summary:" + summary.conversationId() + ":" + summary.version(),
                            "system", summary.summary(), ContextSection.Kind.SUMMARY,
                            summary.throughMessageIndex(), 1, null));
                }
            }
            stored.stream()
                    .filter(message -> !request.runId().equals(message.runId()))
                    .forEach(message -> result.add(new ContextSection(
                            message.id().toString(), role(message.role()), message.content(),
                            ContextSection.Kind.RECENT_MESSAGE, message.messageIndex(), 1,
                            message.runId())));
        } else if (chatMemory != null) {
            List<Message> history = chatMemory.get(request.conversationId());
            for (int i = 0; i < history.size(); i++) {
                Message message = history.get(i);
                result.add(new ContextSection(
                        "history:" + i, role(message.getMessageType().name()),
                        message.getText(), ContextSection.Kind.RECENT_MESSAGE, i, 1,
                        "history-turn:" + (i / 2)));
            }
        }
        if (memories != null) {
            memories.retrieve(request.ownerKey(), request.currentUserMessage(), 20)
                    .forEach(memory -> result.add(new ContextSection(
                            memory.item().id().toString(), "system", memory.item().content(),
                            ContextSection.Kind.LONG_TERM_MEMORY,
                            memory.item().updatedAt().toEpochMilli(), memory.score(), null)));
        }
        return result;
    }

    private String role(String value) {
        return value == null ? "assistant" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
