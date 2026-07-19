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
                "image: postgres:18-alpine",
                "pg_isready",
                "redis:",
                "image: redis:8-alpine",
                "redis-server",
                "--requirepass",
                "healthcheck:",
                "postgres-data:",
                "redis-data:"
        );
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
                "FEISHU_INBOX_REPOSITORY=memory",
                "FEISHU_INBOX_MAX_ATTEMPTS=3",
                "AGENT_SECURITY_ENABLED=false",
                "AGENT_SECURITY_USER_REPOSITORY=memory",
                "AGENT_SECURITY_JWT_SECRET=",
                "AGENT_SECURITY_BOOTSTRAP_USERNAME=",
                "AGENT_SECURITY_BOOTSTRAP_PASSWORD="
        );
    }

    @Test
    void infrastructureDocumentationStatesThatBusinessStateIsNotFullyMigratedYet() throws IOException {
        String document = Files.readString(PROJECT_ROOT.resolve("docs/infrastructure.md"));

        assertThat(document).contains(
                "# Infrastructure",
                "docker compose up -d postgres redis",
                "Flyway is present but disabled by default",
                "Agent trace can now use PostgreSQL",
                "Chat memory can now use PostgreSQL",
                "Tool approvals can now use PostgreSQL",
                "Production must provide explicit database and Redis credentials"
        );
    }
}
