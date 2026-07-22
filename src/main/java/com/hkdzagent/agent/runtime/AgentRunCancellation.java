package com.hkdzagent.agent.runtime;

public record AgentRunCancellation(
        Outcome outcome,
        AgentRun run
) {
    public enum Outcome {
        CANCELLED,
        ALREADY_CANCELLED,
        NOT_FOUND,
        TERMINAL_CONFLICT,
        CONCURRENT_CONFLICT
    }

    public boolean newlyCancelled() {
        return outcome == Outcome.CANCELLED;
    }
}
