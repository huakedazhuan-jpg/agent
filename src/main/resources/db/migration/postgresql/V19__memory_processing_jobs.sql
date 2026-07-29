CREATE TABLE memory_processing_jobs (
    id UUID PRIMARY KEY,
    owner_key VARCHAR(320) NOT NULL,
    conversation_id UUID NOT NULL REFERENCES agent_conversations (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    job_type VARCHAR(40) NOT NULL,
    deduplication_key VARCHAR(256) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_memory_processing_job_type CHECK (
        job_type IN ('UPDATE_SUMMARY', 'EXTRACT_LONG_TERM_MEMORY')
    ),
    CONSTRAINT ck_memory_processing_job_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRYABLE', 'COMPLETED', 'DEAD')
    ),
    CONSTRAINT ck_memory_processing_attempts CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX ux_memory_processing_jobs_deduplication
    ON memory_processing_jobs (deduplication_key);

CREATE INDEX ix_memory_processing_jobs_claim
    ON memory_processing_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRYABLE');
