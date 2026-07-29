package com.hkdzagent.agent.memory;

import java.time.Instant;
import java.util.UUID;

public record MemoryProcessingJob(
        UUID id,
        String ownerKey,
        UUID conversationId,
        String runId,
        JobType jobType,
        String deduplicationKey,
        Status status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public enum JobType {
        UPDATE_SUMMARY,
        EXTRACT_LONG_TERM_MEMORY
    }

    public enum Status {
        PENDING,
        PROCESSING,
        RETRYABLE,
        COMPLETED,
        DEAD
    }
}
