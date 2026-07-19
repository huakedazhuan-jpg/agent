ALTER TABLE tool_approvals
    ADD COLUMN session_id VARCHAR(256),
    ADD COLUMN trace_id VARCHAR(128),
    ADD COLUMN arguments_preview TEXT,
    ADD COLUMN decision_reason TEXT;

UPDATE tool_approvals
SET session_id = 'default'
WHERE session_id IS NULL;

ALTER TABLE tool_approvals
    ALTER COLUMN session_id SET NOT NULL,
    ALTER COLUMN arguments_preview SET DEFAULT '';

UPDATE tool_approvals
SET arguments_preview = ''
WHERE arguments_preview IS NULL;

ALTER TABLE tool_approvals
    ALTER COLUMN arguments_preview SET NOT NULL;

CREATE INDEX ix_tool_approvals_session_status_created_at
    ON tool_approvals (session_id, status, created_at);
