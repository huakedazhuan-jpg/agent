package com.hkdzagent.agent.im;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface FeishuResultOutboxRepository {

    boolean enqueue(FeishuResultOutboxMessage message);

    FeishuResultOutboxMessage claim(
            String id, Instant now, Duration processingTimeout, int maxAttempts);

    void markSent(String id, Instant sentAt);

    void markFailed(
            String id, String error, Instant failedAt,
            Instant nextAttemptAt, boolean terminal);

    List<String> findReadyIds(
            Instant now, Duration processingTimeout, int maxAttempts, int limit);

    int deadLetterExhaustedStale(
            Instant now, Duration processingTimeout, int maxAttempts);

    long countByStatus(FeishuResultOutboxMessage.Status status);

    long countReady(Instant now, Duration processingTimeout, int maxAttempts);

    Instant findOldestOutstandingCreatedAt();

    List<FeishuResultOutboxMessage> findByStatus(
            FeishuResultOutboxMessage.Status status, int limit);

    FeishuResultOutboxMessage retryDead(String id, Instant retryAt);

    FeishuResultOutboxMessage findById(String id);

    FeishuResultOutboxMessage findByDeduplicationKey(String deduplicationKey);
}
