package com.hkdzagent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CiConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path CI_WORKFLOW = PROJECT_ROOT.resolve(".github/workflows/ci.yml");
    private static final Path QUALITY_GATES = PROJECT_ROOT.resolve("docs/quality-gates.md");

    @Test
    void githubActionsWorkflowDefinesBaselineMavenQualityGate() throws IOException {
        String workflow = Files.readString(CI_WORKFLOW);

        assertThat(workflow).contains(
                "name: CI",
                "push:",
                "pull_request:",
                "workflow_dispatch:",
                "permissions:",
                "contents: read",
                "concurrency:",
                "runs-on: ubuntu-latest",
                "timeout-minutes: 20",
                "uses: actions/checkout@v7",
                "uses: actions/setup-java@v5",
                "distribution: temurin",
                "java-version: \"21\"",
                "cache: maven",
                "run: ./mvnw -B --no-transfer-progress test",
                "run: ./mvnw -B --no-transfer-progress -DskipTests package"
        );
    }

    @Test
    void githubActionsWorkflowDoesNotRequireRuntimeSecrets() throws IOException {
        String workflow = Files.readString(CI_WORKFLOW).toLowerCase();

        assertThat(workflow).doesNotContain("secrets.");
    }

    @Test
    void qualityGateDocumentStatesCurrentChecksAndKnownGaps() throws IOException {
        String document = Files.readString(QUALITY_GATES);

        assertThat(document).contains(
                "# Quality Gates",
                "./mvnw -B --no-transfer-progress test",
                "./mvnw -B --no-transfer-progress -DskipTests package",
                "minimum merge gate",
                "Not yet covered",
                "PostgreSQL and Redis integration tests",
                "Docker image build",
                "Do not describe this project as having a complete CI/CD pipeline"
        );
    }
}
