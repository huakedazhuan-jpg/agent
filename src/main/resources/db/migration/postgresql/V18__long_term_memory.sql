CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE agent_memory_items (
    id UUID PRIMARY KEY,
    owner_key VARCHAR(320) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    normalized_key VARCHAR(512) NOT NULL,
    source_conversation_id UUID REFERENCES agent_conversations (id) ON DELETE SET NULL,
    source_message_id UUID REFERENCES agent_messages (id) ON DELETE SET NULL,
    importance DOUBLE PRECISION NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_agent_memory_type CHECK (
        memory_type IN ('USER_PREFERENCE', 'USER_PROFILE', 'PAST_DECISION', 'PROJECT_FACT')
    ),
    CONSTRAINT ck_agent_memory_scores CHECK (
        importance BETWEEN 0 AND 1 AND confidence BETWEEN 0 AND 1
    )
);

CREATE UNIQUE INDEX ux_agent_memory_owner_type_key
    ON agent_memory_items (owner_key, memory_type, normalized_key);

CREATE INDEX ix_agent_memory_owner_status_updated
    ON agent_memory_items (owner_key, status, updated_at DESC);

CREATE INDEX ix_agent_memory_normalized_key_trgm
    ON agent_memory_items USING gin (normalized_key gin_trgm_ops);
