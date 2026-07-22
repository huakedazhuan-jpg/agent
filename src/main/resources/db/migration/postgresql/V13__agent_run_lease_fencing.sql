ALTER TABLE agent_runs
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent_runs
    ADD CONSTRAINT ck_agent_runs_lease_epoch CHECK (lease_epoch >= 0);
