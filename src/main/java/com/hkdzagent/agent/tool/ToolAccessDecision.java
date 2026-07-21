package com.hkdzagent.agent.tool;

public record ToolAccessDecision(boolean allowed, String reason) {

    public ToolAccessDecision {
        if (!allowed && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("denied tool access requires a reason");
        }
    }

    public static ToolAccessDecision allow() {
        return new ToolAccessDecision(true, null);
    }

    public static ToolAccessDecision deny(String reason) {
        return new ToolAccessDecision(false, reason);
    }
}
