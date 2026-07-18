CREATE TABLE agent_traces (
    trace_id VARCHAR(128) PRIMARY KEY,
    session_id VARCHAR(256) NOT NULL,
    user_message TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ
);

CREATE INDEX ix_agent_traces_started_at
    ON agent_traces (started_at DESC);

ALTER TABLE agent_trace_events
    ALTER COLUMN trace_id TYPE VARCHAR(128) USING trace_id::text;

ALTER TABLE agent_trace_events
    ADD COLUMN step INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN tool_name VARCHAR(128),
    ADD COLUMN success BOOLEAN,
    ADD COLUMN content_preview TEXT,
    ADD COLUMN arguments_preview TEXT,
    ADD COLUMN error_message TEXT,
    ADD COLUMN duration_ms BIGINT;

ALTER TABLE agent_trace_events
    ADD CONSTRAINT fk_agent_trace_events_trace
        FOREIGN KEY (trace_id)
        REFERENCES agent_traces (trace_id)
        ON DELETE CASCADE;
