package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.tool.ToolExecutionJournalEvidence;

public class AgentRunRecoveryClassifier {

    public AgentRunRecoveryDecision classify(
            AgentRunRecoveryEvidence evidence,
            ToolExecutionJournalEvidence toolEvidence,
            int maxRecoveryAttempts
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException("recovery evidence must not be null");
        }
        if (maxRecoveryAttempts < 1) {
            throw new IllegalArgumentException("max recovery attempts must be positive");
        }
        if (toolEvidence == null) {
            throw new IllegalArgumentException("tool journal evidence must not be null");
        }
        if (toolEvidence.startedExecutions() > 0) {
            return AgentRunRecoveryDecision.BLOCKED_TOOL_EXECUTION_UNCERTAIN;
        }
        if (toolEvidence.completedExecutions() > 0) {
            return AgentRunRecoveryDecision.BLOCKED_TOOL_CHECKPOINT_MISSING;
        }
        if (evidence.toolExecutionStarted()) {
            return AgentRunRecoveryDecision.BLOCKED_TOOL_JOURNAL_MISSING;
        }
        if (evidence.recoveryAttempts() >= maxRecoveryAttempts) {
            return AgentRunRecoveryDecision.BLOCKED_ATTEMPTS;
        }
        return AgentRunRecoveryDecision.SAFE_RESTART;
    }
}
