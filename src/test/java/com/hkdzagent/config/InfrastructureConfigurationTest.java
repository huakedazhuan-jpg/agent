package com.hkdzagent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void pomDeclaresPostgresRedisAndFlywayInfrastructureDependencies() throws IOException {
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(pom).contains(
                "<artifactId>spring-boot-starter-jdbc</artifactId>",
                "<artifactId>spring-boot-starter-data-redis</artifactId>",
                "<artifactId>flyway-core</artifactId>",
                "<artifactId>flyway-database-postgresql</artifactId>",
                "<artifactId>postgresql</artifactId>",
                "<artifactId>h2</artifactId>"
        );
    }

    @Test
    void applicationYmlDefinesDatabaseRedisAndFlywaySettingsWithoutRequiringDatabaseByDefault() throws IOException {
        String applicationYml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/application.yml"));

        assertThat(applicationYml).contains(
                "datasource:",
                "jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:xingclaw_agent}",
                "username: ${POSTGRES_USER:xingclaw_agent}",
                "password: ${POSTGRES_PASSWORD:xingclaw-local-password}",
                "connection-timeout: ${POSTGRES_CONNECTION_TIMEOUT_MS:2000}",
                "data:",
                "redis:",
                "host: ${REDIS_HOST:localhost}",
                "password: ${REDIS_PASSWORD:xingclaw-local-redis}",
                "repositories:",
                "enabled: false",
                "flyway:",
                "enabled: ${SPRING_FLYWAY_ENABLED:false}",
                "locations: classpath:db/migration/postgresql",
                "sql:",
                "mode: never",
                "trace:",
                "repository: ${AGENT_TRACE_REPOSITORY:memory}"
        );
    }

    @Test
    void dockerComposeDefinesLocalPostgresAndRedisWithHealthChecksAndVolumes() throws IOException {
        String compose = Files.readString(PROJECT_ROOT.resolve("docker-compose.yml"));

        assertThat(compose).contains(
                "postgres:",
                "image: postgres:17-alpine",
                "pg_isready",
                "redis:",
                "image: redis:8-alpine",
                "redis-server",
                "--requirepass",
                "healthcheck:",
                "postgres17-data:",
                "redis-data:"
        );
        assertThat(compose).contains("postgres17-data:/var/lib/postgresql/data");
        assertThat(compose).doesNotContain("image: postgres:18-alpine");
    }

    @Test
    void firstFlywayMigrationCreatesDurableStateSkeletonTables() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V1__agent_infrastructure.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "CREATE TABLE agent_conversations",
                "CREATE TABLE agent_messages",
                "CREATE TABLE agent_trace_events",
                "CREATE TABLE tool_approvals",
                "CREATE TABLE feishu_event_inbox",
                "JSONB",
                "TIMESTAMPTZ"
        );
    }

    @Test
    void secondFlywayMigrationAddsDurableAgentTraceAggregate() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V2__durable_agent_traces.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "CREATE TABLE agent_traces",
                "ALTER TABLE agent_trace_events",
                "ADD COLUMN step INTEGER",
                "ADD CONSTRAINT fk_agent_trace_events_trace",
                "REFERENCES agent_traces (trace_id)"
        );
    }

    @Test
    void thirdFlywayMigrationAddsStableRuntimeStateOrdering() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V3__runtime_state_ordering.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ALTER TABLE agent_messages",
                "ADD COLUMN message_index INTEGER",
                "CREATE INDEX ix_agent_messages_conversation_message_index",
                "ALTER TABLE agent_trace_events",
                "ADD COLUMN event_index INTEGER",
                "CREATE INDEX ix_agent_trace_events_trace_event_index"
        );
    }

    @Test
    void fourthFlywayMigrationAddsDurableToolApprovalFields() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V4__durable_tool_approvals.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ALTER TABLE tool_approvals",
                "ADD COLUMN session_id VARCHAR(256)",
                "ADD COLUMN trace_id VARCHAR(128)",
                "ADD COLUMN arguments_preview TEXT",
                "ADD COLUMN decision_reason TEXT",
                "CREATE INDEX ix_tool_approvals_session_status_created_at"
        );
    }

    @Test
    void fifthFlywayMigrationAddsFeishuInboxLeaseAndRetrySchedule() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V5__durable_feishu_event_inbox.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ALTER TABLE feishu_event_inbox",
                "ADD COLUMN claimed_at TIMESTAMPTZ",
                "ADD COLUMN next_attempt_at TIMESTAMPTZ",
                "CREATE INDEX ix_feishu_event_inbox_retry_schedule"
        );
    }

    @Test
    void sixthFlywayMigrationAddsUsersRolesAndNormalizedUsernameUniqueness() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V6__users_and_roles.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "CREATE TABLE app_users",
                "CREATE UNIQUE INDEX ux_app_users_normalized_username",
                "ON app_users (lower(username))",
                "CREATE TABLE app_roles",
                "CREATE TABLE app_user_roles",
                "REFERENCES app_users (id) ON DELETE CASCADE",
                "VALUES ('USER'), ('ADMIN')"
        );
    }

    @Test
    void seventhFlywayMigrationAddsResourceOwnershipAndOwnerScopedConversationUniqueness() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V7__resource_ownership.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ALTER TABLE agent_conversations",
                "ALTER TABLE agent_traces",
                "ALTER TABLE tool_approvals",
                "ADD COLUMN owner_key VARCHAR(320)",
                "SET owner_key = 'legacy:unowned'",
                "ALTER COLUMN owner_key SET NOT NULL",
                "DROP INDEX ux_agent_conversations_channel_external_id",
                "CREATE UNIQUE INDEX ux_agent_conversations_owner_channel_external_id",
                "ON agent_conversations (owner_key, channel, external_conversation_id)",
                "CREATE INDEX ix_agent_traces_owner_started_at",
                "CREATE INDEX ix_tool_approvals_owner_session_status_created_at"
        );
    }

    @Test
    void eighthFlywayMigrationAddsDurableAgentRuntimeAndOrderedEvents() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V8__durable_agent_runtime.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "CREATE TABLE agent_runs",
                "owner_key VARCHAR(320) NOT NULL",
                "status VARCHAR(32) NOT NULL",
                "version BIGINT NOT NULL DEFAULT 0",
                "last_event_sequence BIGINT NOT NULL DEFAULT 0",
                "checkpoint JSONB NOT NULL",
                "pending_approval_id UUID",
                "lease_owner VARCHAR(128)",
                "ck_agent_runs_pending_approval",
                "ck_agent_runs_completion_time",
                "CREATE TABLE agent_run_events",
                "UNIQUE (run_id, sequence)",
                "ALTER TABLE tool_approvals",
                "ADD COLUMN run_id UUID REFERENCES agent_runs (id) ON DELETE SET NULL"
        );
    }

    @Test
    void ninthFlywayMigrationBindsApprovalsToExactToolInvocations() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V9__bind_tool_approvals_to_invocations.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ADD COLUMN tool_version",
                "ADD COLUMN tool_call_id",
                "ix_tool_approvals_run_tool_call",
                "run_id",
                "tool_call_id"
        );
    }

    @Test
    void tenthFlywayMigrationCreatesDurableFeishuResultOutbox() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V10__durable_feishu_result_outbox.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "CREATE TABLE feishu_result_outbox",
                "run_id UUID NOT NULL REFERENCES agent_runs",
                "CONSTRAINT ux_feishu_result_outbox_run UNIQUE (run_id)",
                "RETRYABLE",
                "ix_feishu_result_outbox_delivery"
        );
    }

    @Test
    void eleventhFlywayMigrationCreatesAdminAuditEvents() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V11__admin_audit_events.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "CREATE TABLE admin_audit_events",
                "actor_key VARCHAR(320) NOT NULL",
                "resource_type",
                "outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')",
                "ix_admin_audit_events_resource"
        );
    }

    @Test
    void twelfthFlywayMigrationGeneralizesFeishuOutboxNotifications() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V12__generalize_feishu_notification_outbox.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ADD COLUMN notification_type VARCHAR(32)",
                "ADD COLUMN deduplication_key VARCHAR(512)",
                "SET notification_type = 'FINAL_RESULT'",
                "DROP CONSTRAINT ux_feishu_result_outbox_run",
                "UNIQUE (deduplication_key)",
                "APPROVAL_REQUIRED",
                "RUN_FAILED",
                "ix_feishu_result_outbox_run_type"
        );
    }

    @Test
    void thirteenthFlywayMigrationAddsAgentRunLeaseFencing() throws IOException {
        Path migration = PROJECT_ROOT.resolve(
                "src/main/resources/db/migration/postgresql/V13__agent_run_lease_fencing.sql"
        );
        String sql = Files.readString(migration);

        assertThat(sql).contains(
                "ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0",
                "ck_agent_runs_lease_epoch",
                "lease_epoch >= 0"
        );
    }

    @Test
    void environmentTemplateDocumentsInfrastructureSettings() throws IOException {
        String envExample = Files.readString(PROJECT_ROOT.resolve(".env.example"));

        assertThat(envExample).contains(
                "POSTGRES_HOST=localhost",
                "POSTGRES_DB=xingclaw_agent",
                "POSTGRES_PASSWORD=xingclaw-local-password",
                "REDIS_HOST=localhost",
                "REDIS_PASSWORD=xingclaw-local-redis",
                "SPRING_FLYWAY_ENABLED=false",
                "AGENT_TRACE_REPOSITORY=memory",
                "AGENT_TOOL_APPROVAL_REPOSITORY=memory",
                "AGENT_TOOL_APPROVAL_TTL=15m",
                "AGENT_MEMORY_REPOSITORY=file",
                "AGENT_AUDIT_REPOSITORY=memory",
                "FEISHU_INBOX_REPOSITORY=memory",
                "FEISHU_INBOX_MAX_ATTEMPTS=3",
                "FEISHU_OUTBOX_REPOSITORY=memory",
                "FEISHU_OUTBOX_MAX_ATTEMPTS=5",
                "AGENT_SECURITY_ENABLED=false",
                "AGENT_SECURITY_USER_REPOSITORY=memory",
                "AGENT_SECURITY_JWT_SECRET=",
                "AGENT_SECURITY_BOOTSTRAP_USERNAME=",
                "AGENT_SECURITY_BOOTSTRAP_PASSWORD="
        );
    }

    @Test
    void infrastructureDocumentationMatchesCurrentDurabilityAndRecoveryBoundary() throws IOException {
        String document = Files.readString(PROJECT_ROOT.resolve("docs/infrastructure.md"));

        assertThat(document).contains(
                "# Infrastructure",
                "docker compose up -d postgres redis",
                "PostgreSQL 17 for durable application state",
                "Migrations V1-V13",
                "Feishu notification outbox",
                "FOR UPDATE SKIP LOCKED",
                "no `TOOL_STARTED` event exists",
                "RUN_RECOVERY_BLOCKED",
                "Redis is present in the infrastructure baseline but is not used",
                "Production must provide explicit database and Redis credentials"
        );
        assertThat(document).doesNotContain("PostgreSQL 18 for durable application state");
    }
}
