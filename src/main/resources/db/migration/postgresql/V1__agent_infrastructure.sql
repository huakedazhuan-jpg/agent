CREATE TABLE agent_conversations (
    id UUID PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,
    external_conversation_id VARCHAR(256),
    title VARCHAR(256),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_agent_conversations_channel_external_id
    ON agent_conversations (channel, external_conversation_id)
    WHERE external_conversation_id IS NOT NULL;

CREATE TABLE agent_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES agent_conversations (id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_agent_messages_conversation_created_at
    ON agent_messages (conversation_id, created_at);

CREATE TABLE agent_trace_events (
    id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    conversation_id UUID REFERENCES agent_conversations (id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_agent_trace_events_trace_created_at
    ON agent_trace_events (trace_id, created_at);

CREATE TABLE tool_approvals (
    id UUID PRIMARY KEY,
    conversation_id UUID REFERENCES agent_conversations (id) ON DELETE SET NULL,
    tool_name VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    request_payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_tool_approvals_status_expires_at
    ON tool_approvals (status, expires_at);

CREATE TABLE feishu_event_inbox (
    event_id VARCHAR(256) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    open_id VARCHAR(256),
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX ix_feishu_event_inbox_status_received_at
    ON feishu_event_inbox (status, received_at);
