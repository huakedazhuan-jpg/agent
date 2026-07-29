package com.hkdzagent.agent.tool;

public record ToolPolicyDecision(Outcome outcome, String reason) {

    public enum Outcome {
        ALLOW,
        APPROVAL_REQUIRED,
        DENY
    }

    public static ToolPolicyDecision allow() {
        return new ToolPolicyDecision(Outcome.ALLOW, null);
    }

    public static ToolPolicyDecision requireApproval(String reason) {
        return new ToolPolicyDecision(Outcome.APPROVAL_REQUIRED, reason);
    }

    public static ToolPolicyDecision deny(String reason) {
        return new ToolPolicyDecision(Outcome.DENY, reason);
    }
}
