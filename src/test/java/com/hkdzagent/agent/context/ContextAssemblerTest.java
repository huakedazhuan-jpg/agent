package com.hkdzagent.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    @Test
    void assemblesDeterministicallyAndKeepsCurrentMessageVerbatim() {
        ContextBudget budget = new ContextBudget();
        ContextSource source = request -> List.of(
                new ContextSection("m2", "assistant", "answer", ContextSection.Kind.RECENT_MESSAGE, 2, 1, "turn-1"),
                new ContextSection("m1", "user", "question", ContextSection.Kind.RECENT_MESSAGE, 1, 1, "turn-1"));
        ContextAssembler assembler =
                new ContextAssembler(source, new ConservativeTokenCounter(), budget);
        ContextRequest request = new ContextRequest(
                "user:a", "conversation", "run", "system", "当前问题不能被截断", List.of());

        ContextEnvelope first = assembler.assemble(request);
        ContextEnvelope second = assembler.assemble(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.messages().get(first.messages().size() - 1).content())
                .isEqualTo("当前问题不能被截断");
        assertThat(first.messages()).extracting(ContextSection::id)
                .containsExactly("system", "m1", "m2", "run:run:user");
    }

    @Test
    void removesPairedAssistantAndToolMessagesTogether() {
        ContextBudget budget = new ContextBudget();
        budget.setRecentMessageTokens(1);
        ContextSource source = request -> List.of(
                new ContextSection("assistant-call", "assistant", "call", ContextSection.Kind.RECENT_MESSAGE, 1, 1, "call-1"),
                new ContextSection("tool-result", "tool", "result", ContextSection.Kind.RECENT_MESSAGE, 2, 1, "call-1"));
        ContextEnvelope envelope = new ContextAssembler(
                source, new ConservativeTokenCounter(), budget).assemble(
                new ContextRequest("user:a", "c", "r", "s", "u", List.of()));

        assertThat(envelope.messages()).extracting(ContextSection::id)
                .doesNotContain("assistant-call", "tool-result");
    }
}
