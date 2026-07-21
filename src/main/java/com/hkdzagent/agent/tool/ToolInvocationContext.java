package com.hkdzagent.agent.tool;

import java.util.UUID;
import java.util.Set;

public record ToolInvocationContext(
        String ownerKey,
        String runId,
        String traceId,
        String toolCallId,
        Set<String> authorities
) {
    public ToolInvocationContext(String ownerKey, String runId, String traceId, String toolCallId) {
        this(ownerKey, runId, traceId, toolCallId, Set.of());
    }

    public ToolInvocationContext {
        requireText(ownerKey, "ownerKey");
        requireUuid(runId, "runId");
        requireText(traceId, "traceId");
        requireText(toolCallId, "toolCallId");
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
        if (authorities.stream().anyMatch(authority -> authority == null || authority.isBlank())) {
            throw new IllegalArgumentException("authorities must not contain blank values");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireUuid(String value, String field) {
        requireText(value, field);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }
}
