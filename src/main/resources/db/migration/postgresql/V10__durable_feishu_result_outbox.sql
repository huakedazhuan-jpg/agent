CREATE TABLE feishu_result_outbox (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    open_id VARCHAR(256) NOT NULL,
    message_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    dead_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    CONSTRAINT ux_feishu_result_outbox_run UNIQUE (run_id),
    CONSTRAINT ck_feishu_result_outbox_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRYABLE', 'SENT', 'DEAD')
    ),
    CONSTRAINT ck_feishu_result_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_feishu_result_outbox_delivery
    ON feishu_result_outbox (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRYABLE');
