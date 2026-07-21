package com.hkdzagent.agent.tool;

public class ToolExecutionPipeline {

    private final ToolInvocationValidator validator;
    private final ToolPolicyEngine policyEngine;

    public ToolExecutionPipeline(ToolInvocationValidator validator, ToolPolicyEngine policyEngine) {
        this.validator = validator;
        this.policyEngine = policyEngine;
    }

    public ToolPipelineResult invoke(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        PreparedInvocation prepared = prepare(context, toolName, argumentsJson);
        if (prepared.result().status() != ToolPipelineResult.Status.READY) {
            return prepared.result();
        }
        return execute(prepared.invocation());
    }

    public ToolPipelineResult assess(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        return prepare(context, toolName, argumentsJson).result();
    }

    public ToolPipelineResult invokeApproved(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson,
            String approvedToolVersion,
            String approvedArgumentsHash
    ) {
        PreparedInvocation prepared = prepare(context, toolName, argumentsJson);
        ToolPipelineResult assessment = prepared.result();
        if (assessment.status() == ToolPipelineResult.Status.REJECTED
                || assessment.status() == ToolPipelineResult.Status.FAILED) {
            return assessment;
        }
        if (!assessment.toolVersion().equals(approvedToolVersion)) {
            return ToolPipelineResult.from(
                    prepared.invocation(), ToolPipelineResult.Status.REJECTED, null,
                    "approved tool version does not match registered tool version");
        }
        if (!assessment.argumentsHash().equals(approvedArgumentsHash)) {
            return ToolPipelineResult.from(
                    prepared.invocation(), ToolPipelineResult.Status.REJECTED, null,
                    "approved arguments hash does not match invocation arguments");
        }
        return execute(prepared.invocation());
    }

    private PreparedInvocation prepare(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        ValidatedToolInvocation<?, ?> invocation;
        try {
            invocation = validator.validate(context, toolName, argumentsJson);
        } catch (ToolInvocationValidationException exception) {
            return new PreparedInvocation(null,
                    ToolPipelineResult.invalid(toolName, exception.getMessage()));
        }

        ToolPolicyDecision decision = policyEngine.evaluate(invocation);
        if (decision.outcome() == ToolPolicyDecision.Outcome.DENY) {
            return new PreparedInvocation(invocation, ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.REJECTED, null, decision.reason()));
        }
        if (decision.outcome() == ToolPolicyDecision.Outcome.APPROVAL_REQUIRED) {
            return new PreparedInvocation(invocation, ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.APPROVAL_REQUIRED, null, decision.reason()));
        }

        return new PreparedInvocation(invocation, ToolPipelineResult.ready(invocation));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ToolPipelineResult execute(ValidatedToolInvocation invocation) {
        try {
            ToolResult result = (ToolResult) invocation.tool().execute(invocation.input());
            if (result == null) {
                return ToolPipelineResult.from(
                        invocation, ToolPipelineResult.Status.FAILED, null, "tool returned no result");
            }
            ToolPipelineResult.Status status = switch (result.status()) {
                case SUCCESS -> ToolPipelineResult.Status.COMPLETED;
                case REJECTED -> ToolPipelineResult.Status.REJECTED;
                case FAILED -> ToolPipelineResult.Status.FAILED;
            };
            return ToolPipelineResult.from(invocation, status, result,
                    status == ToolPipelineResult.Status.COMPLETED ? null : result.message());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.FAILED, null, "tool execution was interrupted");
        } catch (Exception exception) {
            return ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.FAILED, null, "tool execution failed: " + exception.getMessage());
        }
    }

    private record PreparedInvocation(
            ValidatedToolInvocation<?, ?> invocation,
            ToolPipelineResult result
    ) {
    }
}
