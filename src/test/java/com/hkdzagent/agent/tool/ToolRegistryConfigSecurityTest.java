package com.hkdzagent.agent.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ToolRegistryConfigSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void fileOperationToolAllowsWriteInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ToolRegistryConfig config = secureConfig(securityProperties(workspace, List.of(), List.of()));
        Function<ToolRegistryConfig.FileRequest, String> tool = config.fileOperationTool();

        Path target = workspace.resolve("allowed.txt");
        tool.apply(new ToolRegistryConfig.FileRequest(target.toString(), "safe content"));

        assertThat(target).hasContent("safe content");
    }

    @Test
    void fileOperationToolRejectsPathOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        ToolRegistryConfig config = secureConfig(securityProperties(workspace, List.of(), List.of()));
        Function<ToolRegistryConfig.FileRequest, String> tool = config.fileOperationTool();

        String result = tool.apply(new ToolRegistryConfig.FileRequest(outside.toString(), "unsafe content"));

        assertRejected(result);
        assertThat(outside).doesNotExist();
    }

    @Test
    void fileOperationToolRejectsPathTraversalOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside-by-traversal.txt");
        Path traversal = workspace.resolve("..").resolve(outside.getFileName());
        ToolRegistryConfig config = secureConfig(securityProperties(workspace, List.of(), List.of()));
        Function<ToolRegistryConfig.FileRequest, String> tool = config.fileOperationTool();

        String result = tool.apply(new ToolRegistryConfig.FileRequest(traversal.toString(), "unsafe content"));

        assertRejected(result);
        assertThat(outside).doesNotExist();
    }

    @Test
    void commandExecuteToolAllowsWhitelistedCommand() throws Exception {
        ToolRegistryConfig config = secureConfig(securityProperties(tempDir, List.of("echo security-ok"), List.of()));
        Function<ToolRegistryConfig.CommandRequest, String> tool = config.commandExecuteTool();

        String result = tool.apply(new ToolRegistryConfig.CommandRequest("echo security-ok"));

        assertThat(result).contains("security-ok");
    }

    @Test
    void commandExecuteToolRejectsCommandOutsideWhitelist() throws Exception {
        ToolRegistryConfig config = secureConfig(securityProperties(tempDir, List.of("echo security-ok"), List.of()));
        Function<ToolRegistryConfig.CommandRequest, String> tool = config.commandExecuteTool();

        String result = tool.apply(new ToolRegistryConfig.CommandRequest("whoami"));

        assertRejected(result);
    }

    @Test
    void commandExecuteToolRejectsShellControlOperators() throws Exception {
        ToolRegistryConfig config = secureConfig(securityProperties(tempDir, List.of("echo security-ok"), List.of()));
        Function<ToolRegistryConfig.CommandRequest, String> tool = config.commandExecuteTool();

        String result = tool.apply(new ToolRegistryConfig.CommandRequest("echo security-ok & whoami"));

        assertRejected(result);
    }

    @Test
    void httpRequestToolRejectsDomainOutsideAllowList() throws Exception {
        ToolRegistryConfig config = secureConfig(securityProperties(tempDir, List.of(), List.of("localhost")));
        Function<ToolRegistryConfig.WebRequest, String> tool = config.httpRequestTool();

        String result = tool.apply(new ToolRegistryConfig.WebRequest("https://blocked.example.test/data"));

        assertRejected(result);
    }

    @Test
    void httpRequestToolLimitsResponseSize() throws Exception {
        ToolSecurityProperties security = securityProperties(tempDir, List.of(), List.of("localhost"),
                Duration.ofMillis(500), 16);
        ToolRegistryConfig config = secureConfig(security);
        Function<ToolRegistryConfig.WebRequest, String> tool = config.httpRequestTool();

        try (TestHttpServer server = startServer(exchange ->
                writeBody(exchange, "0123456789abcdefEXTRA"))) {
            String result = tool.apply(new ToolRegistryConfig.WebRequest(server.uri("/").toString()));

            assertThat(result).contains("0123456789abcdef");
            assertThat(result).doesNotContain("EXTRA");
        }
    }

    @Test
    void httpRequestToolUsesConfiguredReadTimeout() throws Exception {
        ToolSecurityProperties security = securityProperties(tempDir, List.of(), List.of("localhost"),
                Duration.ofMillis(100), 1024);
        ToolRegistryConfig config = secureConfig(security);
        Function<ToolRegistryConfig.WebRequest, String> tool = config.httpRequestTool();

        try (TestHttpServer server = startServer(exchange -> {
            sleep(Duration.ofSeconds(2));
            writeBody(exchange, "late response");
        })) {
            long startedAt = System.nanoTime();
            String result = tool.apply(new ToolRegistryConfig.WebRequest(server.uri("/").toString()));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
            assertThat(result.toLowerCase(Locale.ROOT)).containsAnyOf("timeout", "timed out");
        }
    }

    @Test
    void httpRequestToolReportsNonSuccessStatusInsteadOfReturningErrorPageAsSuccess() throws Exception {
        ToolRegistryConfig config = secureConfig(
                securityProperties(tempDir, List.of(), List.of("localhost")));
        Function<ToolRegistryConfig.WebRequest, String> tool = config.httpRequestTool();

        try (TestHttpServer server = startServer(exchange ->
                writeBody(exchange, 429, "text/html", "<html>rate limited</html>"))) {
            String result = tool.apply(new ToolRegistryConfig.WebRequest(server.uri("/quote").toString()));

            assertThat(result).contains("http request failed", "status=429", "content-type=text/html");
            assertThat(result).contains("rate limited");
        }
    }

    @Test
    void httpRequestToolFollowsAllowedRedirect() throws Exception {
        ToolRegistryConfig config = secureConfig(
                securityProperties(tempDir, List.of(), List.of("localhost")));
        Function<ToolRegistryConfig.WebRequest, String> tool = config.httpRequestTool();

        try (TestHttpServer server = startServer(exchange -> {
            if ("/redirect".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().add("Location", "/quote");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            writeBody(exchange, "quote-data");
        })) {
            String result = tool.apply(
                    new ToolRegistryConfig.WebRequest(server.uri("/redirect").toString()));

            assertThat(result).isEqualTo("quote-data");
        }
    }

    @Test
    void httpRequestToolRejectsRedirectToDomainOutsideAllowList() throws Exception {
        ToolRegistryConfig config = secureConfig(
                securityProperties(tempDir, List.of(), List.of("localhost")));
        Function<ToolRegistryConfig.WebRequest, String> tool = config.httpRequestTool();

        try (TestHttpServer server = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", "https://blocked.example.test/quote");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        })) {
            String result = tool.apply(
                    new ToolRegistryConfig.WebRequest(server.uri("/redirect").toString()));

            assertRejected(result);
        }
    }

    private static ToolRegistryConfig secureConfig(ToolSecurityProperties securityProperties) {
        try {
            Constructor<ToolRegistryConfig> constructor = ToolRegistryConfig.class
                    .getConstructor(String.class, ToolSecurityProperties.class);
            return constructor.newInstance("test-tavily-key", securityProperties);
        } catch (NoSuchMethodException e) {
            fail("Expected ToolRegistryConfig to require ToolSecurityProperties before registering local tools.");
            return null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not create ToolRegistryConfig with ToolSecurityProperties.", e);
        }
    }

    private static ToolSecurityProperties securityProperties(
            Path workspaceRoot,
            List<String> allowedCommands,
            List<String> allowedDomains
    ) {
        return securityProperties(workspaceRoot, allowedCommands, allowedDomains, Duration.ofMillis(500), 2048);
    }

    private static ToolSecurityProperties securityProperties(
            Path workspaceRoot,
            List<String> allowedCommands,
            List<String> allowedDomains,
            Duration readTimeout,
            long maxResponseBytes
    ) {
        ToolSecurityProperties properties = new ToolSecurityProperties();
        properties.setWorkspaceRoot(workspaceRoot);
        properties.setAllowedCommands(allowedCommands);
        properties.http().setAllowedDomains(allowedDomains);
        properties.http().setConnectTimeout(Duration.ofMillis(500));
        properties.http().setReadTimeout(readTimeout);
        properties.http().setMaxResponseBytes(maxResponseBytes);
        return properties;
    }

    private static void assertRejected(String result) {
        assertThat(result.toLowerCase(Locale.ROOT)).containsAnyOf("rejected", "denied", "not allowed");
    }

    private static TestHttpServer startServer(ThrowingHttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return new TestHttpServer(server);
    }

    private static void writeBody(HttpExchange exchange, String body) throws IOException {
        writeBody(exchange, 200, "text/plain; charset=utf-8", body);
    }

    private static void writeBody(
            HttpExchange exchange,
            int status,
            String contentType,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingHttpHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record TestHttpServer(HttpServer server) implements AutoCloseable {

        URI uri(String path) {
            return URI.create("http://localhost:" + server.getAddress().getPort() + path);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
