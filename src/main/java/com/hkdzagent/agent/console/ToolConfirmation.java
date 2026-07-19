package com.hkdzagent.agent.console;

import java.time.Instant;

public record ToolConfirmation(
        String id,
        String ownerKey,
        String sessionId,
        String traceId,
        String toolName,
        String argumentsPreview,
        Status status,
        String decisionReason,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt
) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }
}
