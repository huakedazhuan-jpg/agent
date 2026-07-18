package com.hkdzagent.agent.console;

import java.time.Instant;

public record ToolConfirmation(
        String id,
        String sessionId,
        String traceId,
        String toolName,
        String argumentsPreview,
        Status status,
        String decisionReason,
        Instant createdAt,
        Instant decidedAt
) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    public ToolConfirmation approve() {
        return new ToolConfirmation(
                id,
                sessionId,
                traceId,
                toolName,
                argumentsPreview,
                Status.APPROVED,
                "approved",
                createdAt,
                Instant.now()
        );
    }

    public ToolConfirmation reject(String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "rejected" : reason;
        return new ToolConfirmation(
                id,
                sessionId,
                traceId,
                toolName,
                argumentsPreview,
                Status.REJECTED,
                normalizedReason,
                createdAt,
                Instant.now()
        );
    }
}
