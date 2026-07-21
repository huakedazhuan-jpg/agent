package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.loop.AgentObservation;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolPipelineResult;

import java.util.Set;

public class ApprovedToolExecutionService {

    private final ToolConfirmationService confirmationService;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;

    public ApprovedToolExecutionService(
            ToolConfirmationService confirmationService,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper
    ) {
        this.confirmationService = confirmationService;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    public ApprovedToolExecution execute(AgentRun run, String approvalId) {
        ToolConfirmation confirmation = confirmationService.findById(approvalId);
        requireApprovedBinding(run, confirmation, approvalId);
        CheckpointInvocation checkpoint = parseCheckpoint(run.checkpointJson());
        requireCheckpointBinding(run, confirmation, checkpoint);

        ToolInvocationContext context = new ToolInvocationContext(
                run.ownerKey(), run.runId(), run.traceId(), checkpoint.toolCallId(), Set.of());
        ToolPipelineResult result = pipeline.invokeApproved(
                context,
                checkpoint.toolName(),
                checkpoint.arguments(),
                confirmation.toolVersion(),
                confirmation.argumentsHash()
        );
        if (result.status() == ToolPipelineResult.Status.READY
                || result.status() == ToolPipelineResult.Status.APPROVAL_REQUIRED) {
            throw new IllegalStateException("approved tool invocation was not executed");
        }
        if (result.toolResult() == null) {
            throw new SecurityException("approved tool invocation was rejected: " + result.reason());
        }
        boolean success = result.status() == ToolPipelineResult.Status.COMPLETED;
        return new ApprovedToolExecution(
                checkpoint.step(),
                checkpoint.toolCallId(),
                new AgentObservation(checkpoint.toolName(), result.toolResult().message(), success)
        );
    }

    private void requireApprovedBinding(
            AgentRun run,
            ToolConfirmation confirmation,
            String approvalId
    ) {
        if (confirmation == null) {
            throw new IllegalArgumentException("confirmation not found: " + approvalId);
        }
        if (confirmation.status() != ToolConfirmation.Status.APPROVED) {
            throw new SecurityException("tool confirmation is not approved");
        }
        if (!run.runId().equals(confirmation.runId())
                || !run.ownerKey().equals(confirmation.ownerKey())
                || !run.traceId().equals(confirmation.traceId())
                || !run.sessionId().equals(confirmation.sessionId())) {
            throw new SecurityException("tool confirmation does not match agent run");
        }
        if (confirmation.toolCallId() == null || confirmation.toolCallId().isBlank()
                || "legacy".equals(confirmation.toolVersion())) {
            throw new SecurityException("legacy tool confirmation cannot resume execution");
        }
    }

    private CheckpointInvocation parseCheckpoint(String checkpointJson) {
        try {
            JsonNode checkpoint = objectMapper.readTree(checkpointJson);
            JsonNode toolCall = checkpoint.path("toolCall");
            String toolCallId = requiredText(toolCall.path("id"), "checkpoint toolCall.id");
            String toolName = requiredText(
                    toolCall.path("function").path("name"), "checkpoint tool name");
            String arguments = requiredText(
                    toolCall.path("function").path("arguments"), "checkpoint tool arguments");
            String traceId = requiredText(checkpoint.path("traceId"), "checkpoint traceId");
            int step = checkpoint.path("step").asInt(-1);
            if (checkpoint.path("schemaVersion").asInt(-1) != 1 || step < 0) {
                throw new IllegalArgumentException("unsupported approval checkpoint schema");
            }
            return new CheckpointInvocation(step, traceId, toolCallId, toolName, arguments);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid approval checkpoint", exception);
        }
    }

    private void requireCheckpointBinding(
            AgentRun run,
            ToolConfirmation confirmation,
            CheckpointInvocation checkpoint
    ) {
        if (!run.traceId().equals(checkpoint.traceId())
                || !confirmation.toolCallId().equals(checkpoint.toolCallId())
                || !confirmation.toolName().equals(checkpoint.toolName())) {
            throw new SecurityException("approval checkpoint does not match tool confirmation");
        }
    }

    private String requiredText(JsonNode node, String field) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return node.asText();
    }

    private record CheckpointInvocation(
            int step,
            String traceId,
            String toolCallId,
            String toolName,
            String arguments
    ) {
    }
}
