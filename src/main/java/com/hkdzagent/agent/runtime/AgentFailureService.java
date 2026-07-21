package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.im.FeishuResultOutboxService;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentFailureService {

    private final AgentRuntimeService runtimeService;
    private final FeishuResultOutboxService outboxService;

    public AgentFailureService(
            AgentRuntimeService runtimeService,
            FeishuResultOutboxService outboxService
    ) {
        this.runtimeService = runtimeService;
        this.outboxService = outboxService;
    }

    @Transactional
    public AgentRunEvent fail(String runId, String workerId, String error) {
        String safeError = normalizeError(error);
        AgentRun failed = runtimeService.fail(runId, workerId, safeError);
        AgentRunEvent event = runtimeService.appendEvent(
                runId, AgentRunEventType.RUN_FAILED, Map.of("error", safeError));
        outboxService.enqueueFailedRun(failed);
        return event;
    }

    @Transactional
    public AgentRunEvent rejectApproval(
            String runId,
            String approvalId,
            String reason
    ) {
        String safeReason = normalizeError(reason);
        AgentRun failed = runtimeService.rejectApproval(runId, approvalId, safeReason);
        AgentRunEvent event = runtimeService.appendEvent(
                runId, AgentRunEventType.RUN_FAILED, Map.of(
                        "error", safeReason,
                        "approvalId", approvalId
                ));
        outboxService.enqueueFailedRun(failed);
        return event;
    }

    private String normalizeError(String error) {
        return error == null || error.isBlank() ? "agent execution failed" : error;
    }
}
