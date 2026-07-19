package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentApprovalPauseService {

    private final ToolConfirmationService confirmationService;
    private final AgentRuntimeService runtimeService;
    private final AgentTraceSanitizer sanitizer;

    public AgentApprovalPauseService(
            ToolConfirmationService confirmationService,
            AgentRuntimeService runtimeService,
            AgentTraceSanitizer sanitizer
    ) {
        this.confirmationService = confirmationService;
        this.runtimeService = runtimeService;
        this.sanitizer = sanitizer;
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
        runtimeService.waitForApproval(
                run.runId(), workerId, confirmation.id(), checkpointJson);
        return runtimeService.appendEvent(run.runId(), AgentRunEventType.APPROVAL_REQUIRED, Map.of(
                "step", step,
                "approvalId", confirmation.id(),
                "toolName", toolName,
                "arguments", sanitizer.preview(arguments)
        ));
    }
}
