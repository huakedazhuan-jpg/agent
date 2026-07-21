package com.hkdzagent.agent.console;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public record ToolConfirmation(
        String id,
        String ownerKey,
        String sessionId,
        String traceId,
        String runId,
        String toolName,
        String toolVersion,
        String toolCallId,
        String argumentsHash,
        String argumentsPreview,
        Status status,
        String decisionReason,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt
) {

    public ToolConfirmation(
            String id, String ownerKey, String sessionId, String traceId,
            String toolName, String argumentsPreview, Status status,
            String decisionReason, Instant createdAt, Instant expiresAt, Instant decidedAt
    ) {
        this(id, ownerKey, sessionId, traceId, null, toolName,
                "legacy", null, legacyHash(argumentsPreview), argumentsPreview,
                status, decisionReason, createdAt, expiresAt, decidedAt);
    }

    public ToolConfirmation(
            String id, String ownerKey, String sessionId, String traceId, String runId,
            String toolName, String argumentsPreview, Status status,
            String decisionReason, Instant createdAt, Instant expiresAt, Instant decidedAt
    ) {
        this(id, ownerKey, sessionId, traceId, runId, toolName,
                "legacy", null, legacyHash(argumentsPreview), argumentsPreview,
                status, decisionReason, createdAt, expiresAt, decidedAt);
    }

    public ToolConfirmation {
        if (argumentsHash == null || !argumentsHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("argumentsHash must be a lowercase SHA-256 hash");
        }
        toolVersion = toolVersion == null || toolVersion.isBlank() ? "legacy" : toolVersion;
    }

    private static String legacyHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to hash legacy approval preview", exception);
        }
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }
}
