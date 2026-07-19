package com.hkdzagent.agent.loop;

import java.util.List;

public record AgentLoopResult(
        Status status,
        String traceId,
        String finalAnswer,
        List<AgentStep> steps
) {

    public enum Status {
        COMPLETED,
        WAITING_APPROVAL,
        STEP_LIMIT_REACHED,
        FAILED
    }
}
