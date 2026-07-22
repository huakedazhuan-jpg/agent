package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentCancellationService {

    private final AgentRuntimeService runtimeService;
    private final AgentTraceRecorder traceRecorder;
    private final AgentTraceSanitizer sanitizer;

    public AgentCancellationService(
            AgentRuntimeService runtimeService,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer
    ) {
        this.runtimeService = runtimeService;
        this.traceRecorder = traceRecorder;
        this.sanitizer = sanitizer;
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
