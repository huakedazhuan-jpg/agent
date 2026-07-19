package com.hkdzagent.agent.runtime;

import java.time.Instant;

public record AgentRunEvent(
        String runId,
        long sequence,
        AgentRunEventType type,
        String payloadJson,
        Instant createdAt
) {

    public AgentRunEvent {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("event runId must not be blank");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
        if (type == null) {
            throw new IllegalArgumentException("event type must not be null");
        }
        payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
