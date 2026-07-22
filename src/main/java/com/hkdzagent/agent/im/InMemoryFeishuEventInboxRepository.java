package com.hkdzagent.agent.im;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryFeishuEventInboxRepository implements FeishuEventInboxRepository {

    private final Map<String, FeishuInboxEvent> events = new LinkedHashMap<>();

    @Override
    public synchronized boolean receive(FeishuInboxEvent event) {
        if (events.containsKey(event.eventId())) {
            return false;
        }
        events.put(event.eventId(), event);
        return true;
    }

    @Override
    public synchronized FeishuInboxEvent claim(
            String eventId,
            Instant now,
            Duration processingTimeout,
            int maxAttempts
    ) {
        FeishuInboxEvent current = events.get(eventId);
        if (!claimable(current, now, processingTimeout, maxAttempts)) {
            return null;
        }
        FeishuInboxEvent claimed = new FeishuInboxEvent(
                current.eventId(),
                current.eventType(),
                current.openId(),
                current.payload(),
                FeishuInboxEvent.Status.PROCESSING,
                current.receivedAt(),
                now,
                null,
                null,
                current.retryCount() + 1,
                current.lastError(),
                current.runId()
        );
        events.put(eventId, claimed);
        return claimed;
    }

    @Override
    public synchronized boolean bindRun(String eventId, int claimAttempt, String runId) {
        FeishuInboxEvent current = events.get(eventId);
        if (!holdsClaim(current, claimAttempt) || current.runId() != null) {
            return false;
        }
        events.put(eventId, new FeishuInboxEvent(
                current.eventId(), current.eventType(), current.openId(), current.payload(),
                current.status(), current.receivedAt(), current.claimedAt(), current.processedAt(),
                current.nextAttemptAt(), current.retryCount(), current.lastError(), runId));
        return true;
    }

    @Override
    public synchronized boolean markProcessed(
            String eventId, int claimAttempt, Instant processedAt
    ) {
        FeishuInboxEvent current = events.get(eventId);
        if (!holdsClaim(current, claimAttempt)) {
            return false;
        }
        events.put(eventId, copyWithOutcome(
                current,
                FeishuInboxEvent.Status.PROCESSED,
                processedAt,
                null,
                null
        ));
        return true;
    }

    @Override
    public synchronized boolean markFailed(
            String eventId,
            int claimAttempt,
            String error,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean terminal
    ) {
        FeishuInboxEvent current = events.get(eventId);
        if (!holdsClaim(current, claimAttempt)) {
            return false;
        }
        events.put(eventId, copyWithOutcome(
                current,
                terminal ? FeishuInboxEvent.Status.DEAD : FeishuInboxEvent.Status.RETRYABLE,
                terminal ? failedAt : null,
                terminal ? null : nextAttemptAt,
                error
        ));
        return true;
    }

    @Override
    public synchronized List<String> findReadyEventIds(
            Instant now,
            Duration processingTimeout,
            int maxAttempts,
            int limit
    ) {
        List<String> ready = new ArrayList<>();
        for (FeishuInboxEvent event : events.values()) {
            if (claimable(event, now, processingTimeout, maxAttempts)) {
                ready.add(event.eventId());
                if (ready.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(ready);
    }

    @Override
    public synchronized int deadLetterExhaustedStale(
            Instant now,
            Duration processingTimeout,
            int maxAttempts
    ) {
        int deadLettered = 0;
        for (FeishuInboxEvent event : List.copyOf(events.values())) {
            if (event.status() == FeishuInboxEvent.Status.PROCESSING
                    && event.retryCount() >= maxAttempts
                    && event.claimedAt() != null
                    && !event.claimedAt().isAfter(now.minus(processingTimeout))) {
                events.put(event.eventId(), copyWithOutcome(
                        event,
                        FeishuInboxEvent.Status.DEAD,
                        now,
                        null,
                        "processing lease expired after final attempt"
                ));
                deadLettered++;
            }
        }
        return deadLettered;
    }

    @Override
    public synchronized FeishuInboxEvent findById(String eventId) {
        return events.get(eventId);
    }

    private boolean claimable(
            FeishuInboxEvent event,
            Instant now,
            Duration processingTimeout,
            int maxAttempts
    ) {
        if (event == null || event.retryCount() >= maxAttempts) {
            return false;
        }
        if (event.status() == FeishuInboxEvent.Status.RECEIVED) {
            return true;
        }
        if (event.status() == FeishuInboxEvent.Status.RETRYABLE) {
            return event.nextAttemptAt() == null || !event.nextAttemptAt().isAfter(now);
        }
        return event.status() == FeishuInboxEvent.Status.PROCESSING
                && event.claimedAt() != null
                && !event.claimedAt().isAfter(now.minus(processingTimeout));
    }

    private FeishuInboxEvent copyWithOutcome(
            FeishuInboxEvent current,
            FeishuInboxEvent.Status status,
            Instant processedAt,
            Instant nextAttemptAt,
            String lastError
    ) {
        return new FeishuInboxEvent(
                current.eventId(),
                current.eventType(),
                current.openId(),
                current.payload(),
                status,
                current.receivedAt(),
                current.claimedAt(),
                processedAt,
                nextAttemptAt,
                current.retryCount(),
                lastError,
                current.runId()
        );
    }

    private boolean holdsClaim(FeishuInboxEvent event, int claimAttempt) {
        return event != null
                && event.status() == FeishuInboxEvent.Status.PROCESSING
                && event.retryCount() == claimAttempt;
    }
}
