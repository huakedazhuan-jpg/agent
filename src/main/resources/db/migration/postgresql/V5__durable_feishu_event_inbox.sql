ALTER TABLE feishu_event_inbox
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX ix_feishu_event_inbox_retry_schedule
    ON feishu_event_inbox (status, next_attempt_at, received_at);
