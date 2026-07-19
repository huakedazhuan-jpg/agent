package com.hkdzagent.agent.im;

import java.time.Instant;

public record FeishuInboxEvent(
        String eventId,
        String eventType,
        String openId,
        String payload,
        Status status,
        Instant receivedAt,
        Instant claimedAt,
        Instant processedAt,
        Instant nextAttemptAt,
        int retryCount,
        String lastError
) {

    public enum Status {
        RECEIVED,
        PROCESSING,
        PROCESSED,
        RETRYABLE,
        DEAD
    }
}
