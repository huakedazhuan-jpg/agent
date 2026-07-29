ALTER TABLE agent_conversations
    ADD COLUMN next_message_index BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent_messages
    ALTER COLUMN message_index TYPE BIGINT;

WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY conversation_id
               ORDER BY message_index, created_at, id
           ) - 1 AS repaired_index
    FROM agent_messages
)
UPDATE agent_messages message
SET message_index = ordered.repaired_index
FROM ordered
WHERE message.id = ordered.id;

UPDATE agent_conversations conversation
SET next_message_index = COALESCE((
    SELECT MAX(message.message_index) + 1
    FROM agent_messages message
    WHERE message.conversation_id = conversation.id
), 0);

DROP INDEX IF EXISTS ix_agent_messages_conversation_message_index;

CREATE UNIQUE INDEX ux_agent_messages_conversation_message_index
    ON agent_messages (conversation_id, message_index);

ALTER TABLE agent_messages
    ADD COLUMN run_id UUID REFERENCES agent_runs (id) ON DELETE SET NULL,
    ADD COLUMN token_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN message_type VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN source VARCHAR(64) NOT NULL DEFAULT 'LEGACY';

CREATE UNIQUE INDEX ux_agent_messages_run_message_type
    ON agent_messages (run_id, message_type)
    WHERE run_id IS NOT NULL AND message_type IN ('USER', 'ASSISTANT');

CREATE INDEX ix_agent_messages_conversation_run
    ON agent_messages (conversation_id, run_id);
