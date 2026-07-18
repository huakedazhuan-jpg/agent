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
                "<artifactId>postgresql</artifactId>"
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
                "mode: never"
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
    void environmentTemplateDocumentsInfrastructureSettings() throws IOException {
        String envExample = Files.readString(PROJECT_ROOT.resolve(".env.example"));

        assertThat(envExample).contains(
                "POSTGRES_HOST=localhost",
                "POSTGRES_DB=xingclaw_agent",
                "POSTGRES_PASSWORD=xingclaw-local-password",
                "REDIS_HOST=localhost",
                "REDIS_PASSWORD=xingclaw-local-redis",
                "SPRING_FLYWAY_ENABLED=false"
        );
    }

    @Test
    void infrastructureDocumentationStatesThatBusinessStateIsNotFullyMigratedYet() throws IOException {
        String document = Files.readString(PROJECT_ROOT.resolve("docs/infrastructure.md"));

        assertThat(document).contains(
                "# Infrastructure",
                "docker compose up -d postgres redis",
                "Flyway is present but disabled by default",
                "These tables are not fully wired into runtime code yet",
                "Production must provide explicit database and Redis credentials"
        );
    }
}
