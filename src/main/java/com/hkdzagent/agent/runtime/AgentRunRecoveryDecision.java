package com.hkdzagent.agent.runtime;

public enum AgentRunRecoveryDecision {
    SAFE_RESTART,
    BLOCKED_TOOL_EXECUTION_UNCERTAIN,
    BLOCKED_TOOL_CHECKPOINT_MISSING,
    BLOCKED_TOOL_JOURNAL_MISSING,
    BLOCKED_ATTEMPTS
}
