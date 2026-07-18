package com.hkdzagent.agent.loop;

public interface AgentToolExecutor {

    AgentObservation execute(String traceId, AgentToolCall toolCall);
}
