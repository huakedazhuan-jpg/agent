package com.hkdzagent.agent.tool;

public record ToolExecutionJournalEvidence(
        int startedExecutions,
        int completedExecutions
) {
    public ToolExecutionJournalEvidence {
        if (startedExecutions < 0 || completedExecutions < 0) {
            throw new IllegalArgumentException("tool execution counts must not be negative");
        }
    }

    public boolean hasExecutions() {
        return startedExecutions > 0 || completedExecutions > 0;
    }
}
