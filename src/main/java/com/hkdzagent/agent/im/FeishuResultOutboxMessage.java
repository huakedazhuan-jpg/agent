package com.hkdzagent.agent.im;

import java.time.Instant;

public record FeishuResultOutboxMessage(
        String id,
        String runId,
        String openId,
        String text,
        Type type,
        String deduplicationKey,
        Status status,
        Instant createdAt,
        Instant claimedAt,
        Instant sentAt,
        Instant deadAt,
        Instant nextAttemptAt,
        int attemptCount,
        String lastError
) {
    public enum Type {
        APPROVAL_REQUIRED,
        FINAL_RESULT,
        RUN_FAILED
    }

    public enum Status {
        PENDING,
        PROCESSING,
        RETRYABLE,
        SENT,
        DEAD
    }

    public FeishuResultOutboxMessage {
        if (type == null) {
            throw new IllegalArgumentException("outbox notification type is required");
        }
        if (deduplicationKey == null || deduplicationKey.isBlank()) {
            throw new IllegalArgumentException("outbox deduplication key is required");
        }
    }
}
