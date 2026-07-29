package com.hkdzagent.agent.im;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryFeishuResultOutboxRepository
        implements FeishuResultOutboxRepository {

    private final Map<String, FeishuResultOutboxMessage> messages =
            new LinkedHashMap<>();
    private final Map<String, String> idsByDeduplicationKey = new LinkedHashMap<>();

    @Override
    public synchronized boolean enqueue(FeishuResultOutboxMessage message) {
        if (messages.containsKey(message.id())
                || idsByDeduplicationKey.containsKey(message.deduplicationKey())) {
            return false;
        }
        messages.put(message.id(), message);
        idsByDeduplicationKey.put(message.deduplicationKey(), message.id());
        return true;
    }

    @Override
    public synchronized FeishuResultOutboxMessage claim(
            String id, Instant now, Duration processingTimeout, int maxAttempts
    ) {
        FeishuResultOutboxMessage current = messages.get(id);
        if (!claimable(current, now, processingTimeout, maxAttempts)) {
            return null;
        }
        FeishuResultOutboxMessage claimed = copy(
                current, FeishuResultOutboxMessage.Status.PROCESSING,
                now, null, null, null,
                current.attemptCount() + 1, current.lastError());
        messages.put(id, claimed);
        return claimed;
    }

    @Override
    public synchronized void markSent(String id, Instant sentAt) {
        FeishuResultOutboxMessage current = messages.get(id);
        if (current == null || current.status() != FeishuResultOutboxMessage.Status.PROCESSING) {
            return;
        }
        messages.put(id, copy(
                current, FeishuResultOutboxMessage.Status.SENT,
                current.claimedAt(), sentAt, null, null,
                current.attemptCount(), null));
    }

    @Override
    public synchronized void markFailed(
            String id, String error, Instant failedAt,
            Instant nextAttemptAt, boolean terminal
    ) {
        FeishuResultOutboxMessage current = messages.get(id);
        if (current == null || current.status() != FeishuResultOutboxMessage.Status.PROCESSING) {
            return;
        }
        messages.put(id, copy(
                current,
                terminal ? FeishuResultOutboxMessage.Status.DEAD
                        : FeishuResultOutboxMessage.Status.RETRYABLE,
                current.claimedAt(), null, terminal ? failedAt : null,
                terminal ? null : nextAttemptAt,
                current.attemptCount(), error));
    }

    @Override
    public synchronized List<String> findReadyIds(
            Instant now, Duration processingTimeout, int maxAttempts, int limit
    ) {
        List<String> ready = new ArrayList<>();
        for (FeishuResultOutboxMessage message : messages.values()) {
            if (claimable(message, now, processingTimeout, maxAttempts)) {
                ready.add(message.id());
                if (ready.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(ready);
    }

    @Override
    public synchronized int deadLetterExhaustedStale(
            Instant now, Duration processingTimeout, int maxAttempts
    ) {
        int deadLettered = 0;
        for (FeishuResultOutboxMessage message : List.copyOf(messages.values())) {
            if (message.status() == FeishuResultOutboxMessage.Status.PROCESSING
                    && message.attemptCount() >= maxAttempts
                    && message.claimedAt() != null
                    && !message.claimedAt().isAfter(now.minus(processingTimeout))) {
                messages.put(message.id(), copy(
                        message, FeishuResultOutboxMessage.Status.DEAD,
                        message.claimedAt(), null, now, null,
                        message.attemptCount(), "delivery lease expired after final attempt"));
                deadLettered++;
            }
        }
        return deadLettered;
    }

    @Override
    public synchronized long countByStatus(FeishuResultOutboxMessage.Status status) {
        return messages.values().stream()
                .filter(message -> message.status() == status)
                .count();
    }

    @Override
    public synchronized long countReady(
            Instant now, Duration processingTimeout, int maxAttempts
    ) {
        return messages.values().stream()
                .filter(message -> claimable(message, now, processingTimeout, maxAttempts))
                .count();
    }

    @Override
    public synchronized Instant findOldestOutstandingCreatedAt() {
        return messages.values().stream()
                .filter(message -> message.status() == FeishuResultOutboxMessage.Status.PENDING
                        || message.status() == FeishuResultOutboxMessage.Status.PROCESSING
                        || message.status() == FeishuResultOutboxMessage.Status.RETRYABLE)
                .map(FeishuResultOutboxMessage::createdAt)
                .min(Instant::compareTo)
                .orElse(null);
    }

    @Override
    public synchronized List<FeishuResultOutboxMessage> findByStatus(
            FeishuResultOutboxMessage.Status status, int limit
    ) {
        return messages.values().stream()
                .filter(message -> message.status() == status)
                .sorted(java.util.Comparator
                        .comparing(FeishuResultOutboxMessage::createdAt)
                        .thenComparing(FeishuResultOutboxMessage::id))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized FeishuResultOutboxMessage retryDead(String id, Instant retryAt) {
        FeishuResultOutboxMessage current = messages.get(id);
        if (current == null || current.status() != FeishuResultOutboxMessage.Status.DEAD) {
            return null;
        }
        FeishuResultOutboxMessage retried = copy(
                current, FeishuResultOutboxMessage.Status.RETRYABLE,
                null, null, null, retryAt, 0, current.lastError());
        messages.put(id, retried);
        return retried;
    }

    @Override
    public synchronized FeishuResultOutboxMessage findById(String id) {
        return messages.get(id);
    }

    @Override
    public synchronized FeishuResultOutboxMessage findByDeduplicationKey(
            String deduplicationKey
    ) {
        String id = idsByDeduplicationKey.get(deduplicationKey);
        return id == null ? null : messages.get(id);
    }

    private boolean claimable(
            FeishuResultOutboxMessage message,
            Instant now,
            Duration processingTimeout,
            int maxAttempts
    ) {
        if (message == null || message.attemptCount() >= maxAttempts) {
            return false;
        }
        if (message.status() == FeishuResultOutboxMessage.Status.PENDING) {
            return true;
        }
        if (message.status() == FeishuResultOutboxMessage.Status.RETRYABLE) {
            return message.nextAttemptAt() == null
                    || !message.nextAttemptAt().isAfter(now);
        }
        return message.status() == FeishuResultOutboxMessage.Status.PROCESSING
                && message.claimedAt() != null
                && !message.claimedAt().isAfter(now.minus(processingTimeout));
    }

    private FeishuResultOutboxMessage copy(
            FeishuResultOutboxMessage current,
            FeishuResultOutboxMessage.Status status,
            Instant claimedAt,
            Instant sentAt,
            Instant deadAt,
            Instant nextAttemptAt,
            int attemptCount,
            String lastError
    ) {
        return new FeishuResultOutboxMessage(
                current.id(), current.runId(), current.openId(), current.text(),
                current.type(), current.deduplicationKey(),
                status, current.createdAt(), claimedAt, sentAt, deadAt,
                nextAttemptAt, attemptCount, lastError);
    }
}
