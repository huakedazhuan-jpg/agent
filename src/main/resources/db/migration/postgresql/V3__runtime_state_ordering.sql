ALTER TABLE agent_messages
    ADD COLUMN message_index INTEGER NOT NULL DEFAULT 0;

CREATE INDEX ix_agent_messages_conversation_message_index
    ON agent_messages (conversation_id, message_index);

ALTER TABLE agent_trace_events
    ADD COLUMN event_index INTEGER NOT NULL DEFAULT 0;

CREATE INDEX ix_agent_trace_events_trace_event_index
    ON agent_trace_events (trace_id, event_index);
