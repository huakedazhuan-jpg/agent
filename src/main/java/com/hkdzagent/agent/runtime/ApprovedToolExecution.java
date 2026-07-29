package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.loop.AgentObservation;

public record ApprovedToolExecution(
        int step,
        String toolCallId,
        AgentObservation observation
) {
}
