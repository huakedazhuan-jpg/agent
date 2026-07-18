package com.hkdzagent.agent.loop;

public record AgentLoopRequest(String userMessage, String sessionId, String traceId) {
}
