ALTER TABLE agent_conversations
    ADD COLUMN owner_key VARCHAR(320);

UPDATE agent_conversations
SET owner_key = 'legacy:unowned'
WHERE owner_key IS NULL;

ALTER TABLE agent_conversations
    ALTER COLUMN owner_key SET NOT NULL;

DROP INDEX ux_agent_conversations_channel_external_id;

CREATE UNIQUE INDEX ux_agent_conversations_owner_channel_external_id
    ON agent_conversations (owner_key, channel, external_conversation_id)
    WHERE external_conversation_id IS NOT NULL;

ALTER TABLE agent_traces
    ADD COLUMN owner_key VARCHAR(320);

UPDATE agent_traces
SET owner_key = 'legacy:unowned'
WHERE owner_key IS NULL;

ALTER TABLE agent_traces
    ALTER COLUMN owner_key SET NOT NULL;

CREATE INDEX ix_agent_traces_owner_started_at
    ON agent_traces (owner_key, started_at DESC, trace_id DESC);

ALTER TABLE tool_approvals
    ADD COLUMN owner_key VARCHAR(320);

UPDATE tool_approvals
SET owner_key = 'legacy:unowned'
WHERE owner_key IS NULL;

ALTER TABLE tool_approvals
    ALTER COLUMN owner_key SET NOT NULL;

CREATE INDEX ix_tool_approvals_owner_session_status_created_at
    ON tool_approvals (owner_key, session_id, status, created_at);
