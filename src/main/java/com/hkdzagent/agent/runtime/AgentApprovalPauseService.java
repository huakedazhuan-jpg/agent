package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolPipelineResult;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentApprovalPauseService {

    private final ToolConfirmationService confirmationService;
    private final AgentRuntimeService runtimeService;
    private final AgentTraceSanitizer sanitizer;
    private final ObjectMapper objectMapper;
    private final FeishuResultOutboxService outboxService;

    public AgentApprovalPauseService(
            ToolConfirmationService confirmationService,
            AgentRuntimeService runtimeService,
            AgentTraceSanitizer sanitizer
    ) {
        this(confirmationService, runtimeService, sanitizer, new ObjectMapper(), null);
    }

    public AgentApprovalPauseService(
            ToolConfirmationService confirmationService,
            AgentRuntimeService runtimeService,
            AgentTraceSanitizer sanitizer,
            ObjectMapper objectMapper
    ) {
        this(confirmationService, runtimeService, sanitizer, objectMapper, null);
    }

    public AgentApprovalPauseService(
            ToolConfirmationService confirmationService,
            AgentRuntimeService runtimeService,
            AgentTraceSanitizer sanitizer,
            ObjectMapper objectMapper,
            FeishuResultOutboxService outboxService
    ) {
        this.confirmationService = confirmationService;
        this.runtimeService = runtimeService;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
    }

    @Transactional
    public AgentRunEvent pause(
            AgentRun run,
            String workerId,
            int step,
            String toolName,
            String arguments,
            String checkpointJson
    ) {
        ToolConfirmation confirmation = confirmationService.requestConfirmationForRun(
                new ActorIdentity(run.ownerKey()), run.sessionId(), run.traceId(), run.runId(),
                toolName, arguments);
        AgentRun waiting = runtimeService.waitForApproval(
                run.runId(), workerId, run.leaseEpoch(), confirmation.id(), checkpointJson);
        AgentRunEvent event = runtimeService.appendSystemEvent(
                run.runId(), AgentRunEventType.APPROVAL_REQUIRED, Map.of(
                "step", step,
                "approvalId", confirmation.id(),
                "toolName", toolName,
                "arguments", sanitizer.preview(arguments)
        ));
        enqueueApprovalNotification(waiting);
        return event;
    }

    @Transactional
    public AgentRunEvent pauseBoundInvocation(
            AgentRun run,
            String workerId,
            int step,
            ToolInvocationContext context,
            String arguments,
            ToolPipelineResult assessment,
            String checkpointJson
    ) {
        validateBinding(run, context, arguments, assessment, checkpointJson);
        ToolConfirmation confirmation = confirmationService.requestConfirmationForAssessment(
                run.sessionId(), context, assessment);
        AgentRun waiting = runtimeService.waitForApproval(
                run.runId(), workerId, run.leaseEpoch(), confirmation.id(), checkpointJson);
        AgentRunEvent event = runtimeService.appendSystemEvent(
                run.runId(), AgentRunEventType.APPROVAL_REQUIRED, Map.of(
                "step", step,
                "approvalId", confirmation.id(),
                "toolName", assessment.toolName(),
                "toolVersion", assessment.toolVersion(),
                "toolCallId", context.toolCallId(),
                "argumentsHash", assessment.argumentsHash(),
                "arguments", assessment.argumentsPreview() == null ? "{}" : assessment.argumentsPreview()
        ));
        enqueueApprovalNotification(waiting);
        return event;
    }

    private void enqueueApprovalNotification(AgentRun waiting) {
        if (outboxService != null) {
            outboxService.enqueueApprovalRequired(waiting);
        }
    }

    private void validateBinding(
            AgentRun run,
            ToolInvocationContext context,
            String arguments,
            ToolPipelineResult assessment,
            String checkpointJson
    ) {
        if (!run.ownerKey().equals(context.ownerKey())
                || !run.runId().equals(context.runId())
                || !run.traceId().equals(context.traceId())) {
            throw new SecurityException("tool invocation context does not match agent run");
        }
        try {
            JsonNode toolCall = objectMapper.readTree(checkpointJson).path("toolCall");
            String checkpointId = toolCall.path("id").asText();
            String checkpointName = toolCall.path("function").path("name").asText();
            String checkpointArguments = toolCall.path("function").path("arguments").asText();
            if (!context.toolCallId().equals(checkpointId)
                    || !assessment.toolName().equals(checkpointName)
                    || !arguments.equals(checkpointArguments)) {
                throw new SecurityException("approval checkpoint does not match assessed tool invocation");
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid approval checkpoint", exception);
        }
    }
}
