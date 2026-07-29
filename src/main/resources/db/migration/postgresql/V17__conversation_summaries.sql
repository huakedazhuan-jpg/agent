CREATE TABLE conversation_summaries (
    conversation_id UUID PRIMARY KEY REFERENCES agent_conversations (id) ON DELETE CASCADE,
    owner_key VARCHAR(320) NOT NULL,
    summary TEXT NOT NULL,
    through_message_index BIGINT NOT NULL DEFAULT -1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_conversation_summaries_version CHECK (version >= 0)
);

CREATE INDEX ix_conversation_summaries_owner
    ON conversation_summaries (owner_key, updated_at DESC);
