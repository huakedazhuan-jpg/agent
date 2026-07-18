package com.hkdzagent.agent.trace;

import java.util.Map;

public record AgentTraceEvent(
        String traceId,
        TraceEventType type,
        int step,
        String toolName,
        Boolean success,
        String contentPreview,
        String argumentsPreview,
        String errorMessage,
        Long durationMs,
        Map<String, Object> metadata
) {
    public AgentTraceEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
