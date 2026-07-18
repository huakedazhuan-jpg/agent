package com.hkdzagent.agent.loop;

import java.util.List;

public record AgentTurn(
        String traceId,
        AgentLoopRequest request,
        AgentPlan plan,
        List<AgentObservation> observations,
        int step
) {
}
