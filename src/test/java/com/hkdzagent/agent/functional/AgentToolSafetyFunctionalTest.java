package com.hkdzagent.agent.functional;

import com.hkdzagent.agent.tool.ToolRegistryConfig;
import com.hkdzagent.agent.tool.ToolSecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolSafetyFunctionalTest {

    @TempDir
    Path tempDir;

    @Test
    void registeredFileToolWritesInsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ToolRegistryConfig config = toolRegistry(workspace);
        Function<ToolRegistryConfig.FileRequest, String> fileTool = config.fileOperationTool();

        Path target = workspace.resolve("functional.txt");
        String result = fileTool.apply(new ToolRegistryConfig.FileRequest(target.toString(), "functional content"));

        assertThat(result).contains("file written successfully");
        assertThat(target).hasContent("functional content");
    }

    @Test
    void registeredFileToolRejectsPathOutsideConfiguredWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ToolRegistryConfig config = toolRegistry(workspace);
        Function<ToolRegistryConfig.FileRequest, String> fileTool = config.fileOperationTool();
        Path outside = tempDir.resolve("outside.txt");

        String result = fileTool.apply(new ToolRegistryConfig.FileRequest(outside.toString(), "blocked"));

        assertRejected(result);
        assertThat(outside).doesNotExist();
    }

    @Test
    void registeredCommandToolRejectsCommandOutsideAllowList() throws Exception {
        ToolRegistryConfig config = toolRegistry(Files.createDirectories(tempDir.resolve("workspace")));
        Function<ToolRegistryConfig.CommandRequest, String> commandTool = config.commandExecuteTool();

        String result = commandTool.apply(new ToolRegistryConfig.CommandRequest("whoami"));

        assertRejected(result);
    }

    @Test
    void registeredHttpToolRejectsDomainOutsideAllowList() throws Exception {
        ToolRegistryConfig config = toolRegistry(Files.createDirectories(tempDir.resolve("workspace")));
        Function<ToolRegistryConfig.WebRequest, String> httpTool = config.httpRequestTool();

        String result = httpTool.apply(new ToolRegistryConfig.WebRequest("https://blocked.example.test/data"));

        assertRejected(result);
    }

    private static ToolRegistryConfig toolRegistry(Path workspaceRoot) {
        ToolSecurityProperties properties = new ToolSecurityProperties();
        properties.setWorkspaceRoot(workspaceRoot);
        properties.setAllowedCommands(List.of("echo functional-ok"));
        properties.http().setAllowedDomains(List.of("localhost"));
        properties.http().setConnectTimeout(Duration.ofMillis(500));
        properties.http().setReadTimeout(Duration.ofMillis(500));
        properties.http().setMaxResponseBytes(2048);
        return new ToolRegistryConfig("test-tavily-key", properties);
    }

    private static void assertRejected(String result) {
        assertThat(result.toLowerCase(Locale.ROOT)).containsAnyOf("rejected", "denied", "not allowed");
    }
}
