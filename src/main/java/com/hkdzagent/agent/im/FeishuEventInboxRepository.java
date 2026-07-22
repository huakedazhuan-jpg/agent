package com.hkdzagent.agent.im;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface FeishuEventInboxRepository {

    boolean receive(FeishuInboxEvent event);

    FeishuInboxEvent claim(String eventId, Instant now, Duration processingTimeout, int maxAttempts);

    boolean bindRun(String eventId, int claimAttempt, String runId);

    boolean markProcessed(String eventId, int claimAttempt, Instant processedAt);

    boolean markFailed(
            String eventId,
            int claimAttempt,
            String error,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean terminal
    );

    List<String> findReadyEventIds(Instant now, Duration processingTimeout, int maxAttempts, int limit);

    int deadLetterExhaustedStale(Instant now, Duration processingTimeout, int maxAttempts);

    FeishuInboxEvent findById(String eventId);
}
