package com.hkdzagent.agent.tool;

public record ValidatedToolInvocation<I, O>(
        ToolInvocationContext context,
        AgentTool<I, O> tool,
        ToolMetadata metadata,
        I input,
        String argumentsHash,
        String argumentsPreview
) {
}
