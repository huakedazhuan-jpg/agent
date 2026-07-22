ALTER TABLE feishu_event_inbox
    ADD COLUMN run_id UUID REFERENCES agent_runs (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_feishu_event_inbox_run_id
    ON feishu_event_inbox (run_id)
    WHERE run_id IS NOT NULL;
