ALTER TABLE feishu_result_outbox
    ADD COLUMN notification_type VARCHAR(32),
    ADD COLUMN deduplication_key VARCHAR(512);

UPDATE feishu_result_outbox
SET notification_type = 'FINAL_RESULT',
    deduplication_key = 'run:' || CAST(run_id AS VARCHAR) || ':final-result';

ALTER TABLE feishu_result_outbox
    ALTER COLUMN notification_type SET NOT NULL,
    ALTER COLUMN deduplication_key SET NOT NULL,
    DROP CONSTRAINT ux_feishu_result_outbox_run,
    ADD CONSTRAINT ux_feishu_result_outbox_deduplication
        UNIQUE (deduplication_key),
    ADD CONSTRAINT ck_feishu_result_outbox_notification_type CHECK (
        notification_type IN ('APPROVAL_REQUIRED', 'FINAL_RESULT', 'RUN_FAILED')
    );

CREATE INDEX ix_feishu_result_outbox_run_type
    ON feishu_result_outbox (run_id, notification_type, created_at);
