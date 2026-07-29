package com.hkdzagent.agent.tool;

public record ToolPipelineResult(
        Status status,
        String toolName,
        String toolVersion,
        String argumentsHash,
        String argumentsPreview,
        ToolResult toolResult,
        String reason
) {
    public enum Status {
        READY,
        COMPLETED,
        APPROVAL_REQUIRED,
        REJECTED,
        FAILED,
        EXECUTION_UNCERTAIN
    }

    static ToolPipelineResult from(
            ValidatedToolInvocation<?, ?> invocation,
            Status status,
            ToolResult result,
            String reason
    ) {
        return new ToolPipelineResult(
                status,
                invocation.metadata().name(),
                invocation.metadata().version(),
                invocation.argumentsHash(),
                invocation.argumentsPreview(),
                result,
                reason
        );
    }

    static ToolPipelineResult invalid(String toolName, String reason) {
        return new ToolPipelineResult(Status.REJECTED, toolName, null, null, null, null, reason);
    }

    static ToolPipelineResult ready(ValidatedToolInvocation<?, ?> invocation) {
        return from(invocation, Status.READY, null, null);
    }

    static ToolPipelineResult executionUncertain(
            ValidatedToolInvocation<?, ?> invocation,
            String reason
    ) {
        return from(invocation, Status.EXECUTION_UNCERTAIN, null, reason);
    }
}
