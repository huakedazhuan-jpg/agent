package com.hkdzagent.agent.tool;

@FunctionalInterface
public interface ToolApprovalCondition {
    boolean requiresApproval(ValidatedToolInvocation<?, ?> invocation);
}
