CREATE TABLE tool_execution_journal (
    run_id UUID NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_version VARCHAR(64) NOT NULL,
    arguments_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_status VARCHAR(16),
    result_message TEXT,
    execution_token UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, tool_call_id),
    CONSTRAINT ck_tool_execution_journal_status CHECK (
        status IN ('STARTED', 'COMPLETED')
    ),
    CONSTRAINT ck_tool_execution_journal_result_status CHECK (
        result_status IS NULL OR result_status IN ('SUCCESS', 'REJECTED', 'FAILED')
    ),
    CONSTRAINT ck_tool_execution_journal_completion CHECK (
        (status = 'STARTED' AND result_status IS NULL AND result_message IS NULL AND completed_at IS NULL)
        OR
        (status = 'COMPLETED' AND result_status IS NOT NULL AND result_message IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_tool_execution_journal_token
    ON tool_execution_journal (execution_token);

CREATE INDEX ix_tool_execution_journal_status_started
    ON tool_execution_journal (status, started_at)
    WHERE status = 'STARTED';
