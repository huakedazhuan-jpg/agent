package com.hkdzagent.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PostgresCiConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void githubActionsWorkflowIsValidYaml() throws IOException {
        String workflow = Files.readString(PROJECT_ROOT.resolve(".github/workflows/ci.yml"));

        assertThatCode(() -> new Yaml().load(workflow)).doesNotThrowAnyException();
    }

    @Test
    void ciRunsRealPostgresRuntimeAndDatabaseRestartRecoveryGates() throws IOException {
        String workflow = Files.readString(PROJECT_ROOT.resolve(".github/workflows/ci.yml"));

        assertThat(workflow).contains(
                "postgres-runtime:",
                "name: PostgreSQL runtime integration",
                "image: postgres:17-alpine",
                "RUN_POSTGRES_INTEGRATION_TESTS: \"true\"",
                "--health-cmd \"pg_isready -U xingclaw_agent -d xingclaw_agent\"",
                "-Dtest=RealPostgresAgentRuntimeIntegrationTest test",
                "POSTGRES_RECOVERY_PHASE: seed",
                "POSTGRES_CONTAINER_ID: ${{ job.services.postgres.id }}",
                "docker restart \"$POSTGRES_CONTAINER_ID\"",
                "POSTGRES_RECOVERY_PHASE: verify",
                "-Dtest=RealPostgresContainerRestartRecoveryTest test"
        );
    }

    @Test
    void postgresCiUsesOnlyNonSecretEphemeralTestCredentials() throws IOException {
        String workflow = Files.readString(PROJECT_ROOT.resolve(".github/workflows/ci.yml"));
        String postgresJob = workflow.substring(workflow.indexOf("  postgres-runtime:"));

        assertThat(postgresJob).contains("POSTGRES_PASSWORD: xingclaw-ci-password");
        assertThat(postgresJob.toLowerCase()).doesNotContain("secrets.");
    }
}
