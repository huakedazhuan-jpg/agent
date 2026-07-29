package com.hkdzagent.agent.context;

import java.util.List;
import java.util.Objects;

public record ContextRequest(
        String ownerKey,
        String conversationId,
        String runId,
        String systemPrompt,
        String currentUserMessage,
        List<ContextSection> toolObservations
) {
    public ContextRequest {
        ownerKey = require(ownerKey, "ownerKey");
        conversationId = require(conversationId, "conversationId");
        runId = require(runId, "runId");
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        currentUserMessage = Objects.requireNonNullElse(currentUserMessage, "");
        toolObservations = toolObservations == null ? List.of() : List.copyOf(toolObservations);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
