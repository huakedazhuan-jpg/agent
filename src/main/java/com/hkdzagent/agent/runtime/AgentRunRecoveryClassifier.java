package com.hkdzagent.agent.runtime;

public class AgentRunRecoveryClassifier {

    public AgentRunRecoveryDecision classify(
            AgentRunRecoveryEvidence evidence,
            int maxRecoveryAttempts
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException("recovery evidence must not be null");
        }
        if (maxRecoveryAttempts < 1) {
            throw new IllegalArgumentException("max recovery attempts must be positive");
        }
        if (evidence.toolExecutionStarted()) {
            return AgentRunRecoveryDecision.BLOCKED_TOOL_EFFECT;
        }
        if (evidence.recoveryAttempts() >= maxRecoveryAttempts) {
            return AgentRunRecoveryDecision.BLOCKED_ATTEMPTS;
        }
        return AgentRunRecoveryDecision.SAFE_RESTART;
    }
}
