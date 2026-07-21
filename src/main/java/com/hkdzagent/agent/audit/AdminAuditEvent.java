package com.hkdzagent.agent.audit;

import java.time.Instant;

public record AdminAuditEvent(
        String id,
        String actorKey,
        String action,
        String resourceType,
        String resourceId,
        Outcome outcome,
        String detail,
        Instant createdAt
) {
    public enum Outcome {
        SUCCEEDED,
        REJECTED,
        FAILED
    }

    public AdminAuditEvent {
        require(id, "audit id");
        require(actorKey, "audit actor key");
        require(action, "audit action");
        require(resourceType, "audit resource type");
        require(resourceId, "audit resource id");
        if (outcome == null) {
            throw new IllegalArgumentException("audit outcome is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("audit created-at is required");
        }
        if (detail != null && detail.length() > 500) {
            throw new IllegalArgumentException("audit detail exceeds 500 characters");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
