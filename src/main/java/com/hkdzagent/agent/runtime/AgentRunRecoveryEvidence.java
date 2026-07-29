package com.hkdzagent.agent.runtime;

public record AgentRunRecoveryEvidence(
        boolean toolExecutionStarted,
        int recoveryAttempts
) {

    public AgentRunRecoveryEvidence {
        if (recoveryAttempts < 0) {
            throw new IllegalArgumentException("recovery attempts must not be negative");
        }
    }
}
