package com.hkdzagent.agent.tool;

public class ToolPolicyEngine {

    private final ToolAccessPolicy accessPolicy;
    private final ToolApprovalCondition approvalCondition;

    public ToolPolicyEngine(ToolAccessPolicy accessPolicy, ToolApprovalCondition approvalCondition) {
        this.accessPolicy = accessPolicy;
        this.approvalCondition = approvalCondition;
    }

    public ToolPolicyDecision evaluate(ValidatedToolInvocation<?, ?> invocation) {
        ToolAccessDecision access = accessPolicy.authorize(invocation.context(), invocation.metadata());
        if (access == null || !access.allowed()) {
            return ToolPolicyDecision.deny(access == null ? "tool access policy returned no decision" : access.reason());
        }

        return switch (invocation.metadata().approvalPolicy()) {
            case NEVER -> ToolPolicyDecision.allow();
            case ALWAYS -> ToolPolicyDecision.requireApproval("tool policy always requires approval");
            case CONDITIONAL -> approvalCondition.requiresApproval(invocation)
                    ? ToolPolicyDecision.requireApproval("tool policy condition requires approval")
                    : ToolPolicyDecision.allow();
        };
    }
}
