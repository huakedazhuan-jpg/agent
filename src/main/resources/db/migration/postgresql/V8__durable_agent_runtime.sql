CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    owner_key VARCHAR(320) NOT NULL,
    session_id VARCHAR(256) NOT NULL,
    conversation_id VARCHAR(1024) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    user_message TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step INTEGER NOT NULL DEFAULT 0,
    max_steps INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    pending_approval_id UUID,
    final_answer TEXT,
    error_message TEXT,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_agent_runs_status CHECK (
        status IN ('CREATED', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_agent_runs_step_bounds CHECK (
        current_step >= 0 AND max_steps > 0 AND current_step <= max_steps
    ),
    CONSTRAINT ck_agent_runs_version CHECK (version >= 0 AND last_event_sequence >= 0),
    CONSTRAINT ck_agent_runs_pending_approval CHECK (
        status <> 'WAITING_APPROVAL' OR pending_approval_id IS NOT NULL
    ),
    CONSTRAINT ck_agent_runs_completion_time CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
        OR
        (status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX ux_agent_runs_trace_id
    ON agent_runs (trace_id);

CREATE INDEX ix_agent_runs_owner_created_at
    ON agent_runs (owner_key, created_at DESC, id DESC);

CREATE INDEX ix_agent_runs_runnable_lease
    ON agent_runs (status, lease_expires_at, updated_at)
    WHERE status IN ('CREATED', 'RUNNING');

CREATE TABLE agent_run_events (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_agent_run_events_run_sequence UNIQUE (run_id, sequence)
);

CREATE INDEX ix_agent_run_events_run_created_at
    ON agent_run_events (run_id, sequence, created_at);

ALTER TABLE tool_approvals
    ADD COLUMN run_id UUID REFERENCES agent_runs (id) ON DELETE SET NULL;

CREATE INDEX ix_tool_approvals_run_status
    ON tool_approvals (run_id, status)
    WHERE run_id IS NOT NULL;
