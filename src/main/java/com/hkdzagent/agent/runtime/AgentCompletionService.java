package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.im.FeishuResultOutboxService;
import org.springframework.transaction.annotation.Transactional;

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
    public AgentRun complete(String runId, String workerId, String answer) {
        AgentRun completed = runtimeService.complete(runId, workerId, answer);
        outboxService.enqueueCompletedRun(completed);
        return completed;
    }
}
