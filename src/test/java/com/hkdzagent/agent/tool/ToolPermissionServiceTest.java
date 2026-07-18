package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ToolPermissionServiceTest {

    private static final String TOOL_PACKAGE = "com.hkdzagent.agent.tool.";

    @TempDir
    Path tempDir;

    @Test
    void resolvesOnlyPathsInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Object service = newPermissionService(securityProperties(workspace, List.of(), List.of()));
        Method resolveWorkspacePath = service.getClass().getMethod("resolveWorkspacePath", String.class);

        Path allowed = (Path) resolveWorkspacePath.invoke(service, "nested/allowed.txt");
        Path rejected = (Path) resolveWorkspacePath.invoke(service, tempDir.resolve("outside.txt").toString());

        assertThat(allowed).isEqualTo(workspace.resolve("nested/allowed.txt").normalize().toAbsolutePath());
        assertThat(rejected).isNull();
    }

    @Test
    void validatesAllowedCommandsAndRejectsShellControlOperators() throws Exception {
        Object service = newPermissionService(securityProperties(tempDir, List.of("echo security-ok"), List.of()));
        Method isCommandAllowed = service.getClass().getMethod("isCommandAllowed", String.class);

        assertThat((Boolean) isCommandAllowed.invoke(service, " echo security-ok ")).isTrue();
        assertThat((Boolean) isCommandAllowed.invoke(service, "whoami")).isFalse();
        assertThat((Boolean) isCommandAllowed.invoke(service, "echo security-ok & whoami")).isFalse();
    }

    @Test
    void validatesHttpUrisAgainstConfiguredDomainAllowList() throws Exception {
        Object service = newPermissionService(securityProperties(tempDir, List.of(), List.of("example.com")));
        Method isHttpUriAllowed = service.getClass().getMethod("isHttpUriAllowed", URI.class);

        assertThat((Boolean) isHttpUriAllowed.invoke(service, URI.create("https://example.com/data"))).isTrue();
        assertThat((Boolean) isHttpUriAllowed.invoke(service, URI.create("http://example.com/data"))).isTrue();
        assertThat((Boolean) isHttpUriAllowed.invoke(service, URI.create("https://api.example.com/data"))).isFalse();
        assertThat((Boolean) isHttpUriAllowed.invoke(service, URI.create("file:///tmp/data"))).isFalse();
    }

    @Test
    void exposesConfiguredTimeoutAndResponseLimitDefaults() throws Exception {
        Object service = newPermissionService(securityProperties(tempDir, List.of(), List.of()));

        assertThat(invoke(service, "connectTimeout")).isEqualTo(Duration.ofSeconds(2));
        assertThat(invoke(service, "readTimeout")).isEqualTo(Duration.ofSeconds(5));
        assertThat(invoke(service, "maxResponseBytes")).isEqualTo(2048);
    }

    private static Object newPermissionService(ToolSecurityProperties properties) throws Exception {
        Class<?> serviceClass = loadClass("ToolPermissionService");
        return serviceClass.getConstructor(ToolSecurityProperties.class).newInstance(properties);
    }

    private static ToolSecurityProperties securityProperties(
            Path workspaceRoot,
            List<String> allowedCommands,
            List<String> allowedDomains
    ) {
        ToolSecurityProperties properties = new ToolSecurityProperties();
        properties.setWorkspaceRoot(workspaceRoot);
        properties.setAllowedCommands(allowedCommands);
        properties.http().setAllowedDomains(allowedDomains);
        return properties;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static Class<?> loadClass(String simpleName) {
        String className = TOOL_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }
}
