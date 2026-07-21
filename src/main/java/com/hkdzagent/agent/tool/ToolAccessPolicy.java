package com.hkdzagent.agent.tool;

@FunctionalInterface
public interface ToolAccessPolicy {

    ToolAccessDecision authorize(ToolInvocationContext context, ToolMetadata metadata);

    static ToolAccessPolicy allowAuthenticated() {
        return (context, metadata) -> context.ownerKey() == null || context.ownerKey().isBlank()
                ? ToolAccessDecision.deny("authenticated tool owner is required")
                : ToolAccessDecision.allow();
    }
}
