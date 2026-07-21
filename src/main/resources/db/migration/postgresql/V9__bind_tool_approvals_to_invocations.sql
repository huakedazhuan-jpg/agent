ALTER TABLE tool_approvals
    ADD COLUMN tool_version VARCHAR(64) NOT NULL DEFAULT 'legacy';

ALTER TABLE tool_approvals
    ADD COLUMN tool_call_id VARCHAR(128);

CREATE INDEX ix_tool_approvals_run_tool_call
    ON tool_approvals (run_id, tool_call_id)
    WHERE run_id IS NOT NULL AND tool_call_id IS NOT NULL;
