package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.im.FeishuResultOutboxService;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentCompletionService {

    private final AgentRuntimeService runtimeService;
    private final FeishuResultOutboxService outboxService;

    public AgentCompletionService(
            AgentRuntimeService runtimeService,
            FeishuResultOutboxService outboxService
    ) {
        this.runtimeService = runtimeService;
        this.outboxService = outboxService;
    }

    @Transactional
    public AgentRunCompletion complete(
            String runId, String workerId, long leaseEpoch, String answer
    ) {
        AgentRun completed = runtimeService.complete(runId, workerId, leaseEpoch, answer);
        AgentRunEvent event = runtimeService.appendSystemEvent(
                runId, AgentRunEventType.RUN_COMPLETED,
                Map.of("answer", answer == null ? "" : answer));
        outboxService.enqueueCompletedRun(completed);
        return new AgentRunCompletion(completed, event);
    }
}
