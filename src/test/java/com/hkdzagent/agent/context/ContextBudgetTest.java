package com.hkdzagent.agent.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextBudgetTest {

    @Test
    void rejectsCurrentMessageAboveHardLimitInsteadOfTruncatingIt() {
        ContextBudget budget = new ContextBudget();
        budget.setMaxCurrentUserTokens(2);
        ContextAssembler assembler =
                new ContextAssembler(new ConservativeTokenCounter(), budget);

        assertThatThrownBy(() -> assembler.assemble(
                new ContextRequest("user:a", "c", "r", "s", "这是一条过长消息", java.util.List.of())))
                .isInstanceOf(ContextLimitExceededException.class)
                .hasMessageContaining("hard limit");
    }
}
