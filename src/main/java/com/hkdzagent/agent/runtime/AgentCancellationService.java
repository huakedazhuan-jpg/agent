package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentCancellationService {

    private final AgentRuntimeService runtimeService;
    private final AgentTraceRecorder traceRecorder;
    private final AgentTraceSanitizer sanitizer;
    private final ToolConfirmationService confirmationService;

    public AgentCancellationService(
            AgentRuntimeService runtimeService,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer
    ) {
        this(runtimeService, traceRecorder, sanitizer, null);
    }

    public AgentCancellationService(
            AgentRuntimeService runtimeService,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            ToolConfirmationService confirmationService
    ) {
        this.runtimeService = runtimeService;
        this.traceRecorder = traceRecorder;
        this.sanitizer = sanitizer;
        this.confirmationService = confirmationService;
    }

    @Transactional
    public AgentRunCancellation cancel(
            ActorIdentity owner,
            String runId,
            String reason
    ) {
        AgentRunCancellation result = runtimeService.cancelOwned(runId, owner);
        if (!result.newlyCancelled()) {
            return result;
        }
        if (confirmationService != null && result.pendingApprovalId() != null) {
            confirmationService.cancelPending(
                    result.pendingApprovalId(), "agent run cancelled by owner");
        }
        String safeReason = normalizeReason(reason);
        runtimeService.appendSystemEvent(
                runId,
                AgentRunEventType.RUN_CANCELLED,
                Map.of("reason", safeReason, "actor", owner.key()));
        try {
            traceRecorder.finishTrace(result.run().traceId(), "CANCELLED");
        } catch (RuntimeException ignored) {
            // Cancellation state must not be retried because optional trace finalization failed.
        }
        return result;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "cancelled by owner";
        }
        return sanitizer.preview(reason);
    }
}
