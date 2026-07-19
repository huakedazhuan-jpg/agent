package com.hkdzagent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectCleanupTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void frontendTextUsesCleanChineseCopy() throws IOException {
        String html = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/index.html"));

        assertThat(html).contains("<title>XingClaw Agent 控制台</title>");
        assertThat(html).contains("发送");
        assertThat(html).contains("Agent 正在思考");
        assertThat(html).contains("工具调用", "Trace", "敏感操作确认");
        assertThat(html).doesNotContain("鎺", "鍙", "璇", "鐢", "浣", "\uFFFD");
    }

    @Test
    void frontendConsoleUsesStreamingTraceAndConfirmationApis() throws IOException {
        String html = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/index.html"));

        assertThat(html).contains("/api/agent/chat/stream");
        assertThat(html).contains("/api/agent/traces");
        assertThat(html).contains("/api/agent/tool-confirmations");
        assertThat(html).contains("const payload = data.payload || data");
        assertThat(html).contains("state.assistantText += payload.delta");
        assertThat(html).contains("async function followRun(runId)");
        assertThat(html).contains("/events?after=");
        assertThat(html).contains("id=\"messageInput\"");
        assertThat(html).contains("id=\"sendButton\"");
        assertThat(html).contains("id=\"toolTimeline\"");
        assertThat(html).contains("id=\"tracePanel\"");
        assertThat(html).contains("id=\"confirmationQueue\"");
        assertThat(html).contains("id=\"confirmDialog\"");
    }

    @Test
    void devConfigurationUsesYamlSyntax() throws IOException {
        String devConfig = Files.readString(PROJECT_ROOT.resolve("src/main/resources/application-dev.yml"));

        assertThat(devConfig).contains("spring:");
        assertThat(devConfig).doesNotContain("spring.application.name=");
    }

    @Test
    void gitignoreExcludesLocalEnvironmentFiles() throws IOException {
        String gitignore = Files.readString(PROJECT_ROOT.resolve(".gitignore"));

        assertThat(gitignore).contains(".env");
        assertThat(gitignore).contains(".env.*");
        assertThat(gitignore).contains("!.env.example");
    }

    @Test
    void deprecatedEmptyClassesAreRemoved() {
        List<String> removedFiles = List.of(
                "src/main/java/com/hkdzagent/agent/tool/FileOperationTool.java",
                "src/main/java/com/hkdzagent/agent/tool/BashExecutionTool.java",
                "src/main/java/com/hkdzagent/agent/service/AgentLoopService.java",
                "src/main/java/com/hkdzagent/agent/session/MemoryManager.java"
        );

        assertThat(removedFiles)
                .noneSatisfy(file -> assertThat(PROJECT_ROOT.resolve(file)).exists());
    }

    @Test
    void sourceFilesDoNotContainReplacementCharacters() throws IOException {
        try (Stream<Path> paths = Files.walk(PROJECT_ROOT.resolve("src/main"))) {
            List<Path> filesWithReplacementCharacters = paths
                    .filter(Files::isRegularFile)
                    .filter(ProjectCleanupTest::isTextFile)
                    .filter(path -> contains(path, "\uFFFD"))
                    .toList();

            assertThat(filesWithReplacementCharacters).isEmpty();
        }
    }

    private static boolean isTextFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".html")
                || fileName.endsWith(".md");
    }

    private static boolean contains(Path path, String marker) {
        try {
            return Files.readString(path).contains(marker);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
