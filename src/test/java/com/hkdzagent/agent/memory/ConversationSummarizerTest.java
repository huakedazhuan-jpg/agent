package com.hkdzagent.agent.memory;

import com.hkdzagent.agent.context.ConservativeTokenCounter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSummarizerTest {

    @Test
    void processesOnlyMessagesAfterPreviousBoundaryAndUsesOptimisticVersion() {
        UUID conversationId = UUID.randomUUID();
        AtomicReference<ConversationSummary> state = new AtomicReference<>(
                new ConversationSummary(conversationId, "user:a", "old", 1, 3,
                        Instant.EPOCH, Instant.EPOCH));
        ConversationSummaryRepository repository = repository(state);
        AtomicReference<String> prompt = new AtomicReference<>();
        ConversationSummarizer summarizer = new ConversationSummarizer(
                repository, new ConservativeTokenCounter(),
                value -> { prompt.set(value); return "new"; }, 1);

        boolean updated = summarizer.summarize("user:a", conversationId, List.of(
                message(conversationId, 1, "already summarized"),
                message(conversationId, 2, "new message")));

        assertThat(updated).isTrue();
        assertThat(prompt.get()).contains("new message").doesNotContain("already summarized");
        assertThat(state.get().throughMessageIndex()).isEqualTo(2);
        assertThat(state.get().version()).isEqualTo(4);
    }

    @Test
    void modelFailureDoesNotBreakChatPathOrAdvanceBoundary() {
        UUID id = UUID.randomUUID();
        AtomicReference<ConversationSummary> state = new AtomicReference<>(
                new ConversationSummary(id, "user:a", "", -1, 0, Instant.EPOCH, Instant.EPOCH));
        ConversationSummarizer summarizer = new ConversationSummarizer(
                repository(state), new ConservativeTokenCounter(),
                ignored -> { throw new IllegalStateException("offline"); }, 1);

        assertThat(summarizer.summarize("user:a", id, List.of(message(id, 0, "hello"))))
                .isFalse();
        assertThat(state.get().throughMessageIndex()).isEqualTo(-1);
    }

    private ConversationSummaryRepository repository(AtomicReference<ConversationSummary> state) {
        return new ConversationSummaryRepository() {
            public ConversationSummary find(UUID id, String owner) { return state.get(); }
            public ConversationSummary createIfAbsent(UUID id, String owner) { return state.get(); }
            public boolean update(UUID id, String owner, long version, String summary, long through) {
                ConversationSummary current = state.get();
                if (current.version() != version) return false;
                state.set(new ConversationSummary(id, owner, summary, through, version + 1,
                        current.createdAt(), Instant.now()));
                return true;
            }
        };
    }

    private StoredConversationMessage message(UUID conversationId, long index, String content) {
        return new StoredConversationMessage(
                UUID.randomUUID(), conversationId, "user:a", UUID.randomUUID().toString(),
                index, "USER", content, 10, "USER", "RUN_USER", Instant.now());
    }
}
