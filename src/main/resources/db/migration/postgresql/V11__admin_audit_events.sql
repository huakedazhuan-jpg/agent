CREATE TABLE admin_audit_events (
    id UUID PRIMARY KEY,
    actor_key VARCHAR(320) NOT NULL,
    action VARCHAR(96) NOT NULL,
    resource_type VARCHAR(96) NOT NULL,
    resource_id VARCHAR(256) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_audit_events_outcome CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
    )
);

CREATE INDEX ix_admin_audit_events_created_at
    ON admin_audit_events (created_at DESC, id);

CREATE INDEX ix_admin_audit_events_resource
    ON admin_audit_events (resource_type, resource_id, created_at DESC);
