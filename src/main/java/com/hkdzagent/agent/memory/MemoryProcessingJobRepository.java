package com.hkdzagent.agent.memory;

import java.time.Instant;
import java.util.List;

public interface MemoryProcessingJobRepository {

    MemoryProcessingJobRepository NOOP = new MemoryProcessingJobRepository() {
        @Override
        public void enqueue(String ownerKey, String externalConversationId, String runId,
                            MemoryProcessingJob.JobType type) {
        }
        @Override
        public List<MemoryProcessingJob> claim(int limit, Instant now) { return List.of(); }
        @Override
        public void complete(java.util.UUID id, Instant now) { }
        @Override
        public void fail(java.util.UUID id, String error, Instant retryAt, int maxAttempts) { }
        @Override
        public boolean retry(java.util.UUID id, Instant now) { return false; }
    };

    void enqueue(
            String ownerKey,
            String externalConversationId,
            String runId,
            MemoryProcessingJob.JobType type);

    List<MemoryProcessingJob> claim(int limit, Instant now);

    void complete(java.util.UUID id, Instant now);

    void fail(java.util.UUID id, String error, Instant retryAt, int maxAttempts);

    boolean retry(java.util.UUID id, Instant now);
}
