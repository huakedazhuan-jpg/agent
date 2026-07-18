package com.hkdzagent.agent.tool;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class ToolPermissionService {

    private final ToolSecurityProperties securityProperties;

    public ToolPermissionService(ToolSecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public Path resolveWorkspacePath(String filePath) {
        Path workspaceRoot = workspaceRoot();
        if (workspaceRoot == null || filePath == null || filePath.isBlank()) {
            return null;
        }

        Path requestedPath = Path.of(filePath);
        Path resolvedPath = requestedPath.isAbsolute()
                ? requestedPath
                : workspaceRoot.resolve(requestedPath);
        resolvedPath = resolvedPath.normalize().toAbsolutePath();

        if (!resolvedPath.startsWith(workspaceRoot)) {
            return null;
        }
        return resolvedPath;
    }

    public boolean isCommandAllowed(String command) {
        String normalizedCommand = normalizeCommand(command);
        return normalizedCommand != null
                && !containsShellControlOperator(normalizedCommand)
                && allowedCommands().contains(normalizedCommand);
    }

    public String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        return command.trim();
    }

    public boolean isHttpUriAllowed(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return false;
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return allowedDomains().stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedHost::equals);
    }

    public Duration connectTimeout() {
        Duration connectTimeout = securityProperties.http().connectTimeout();
        return connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    }

    public Duration readTimeout() {
        Duration readTimeout = securityProperties.http().readTimeout();
        return readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    public int maxResponseBytes() {
        long maxResponseBytes = securityProperties.http().maxResponseBytes();
        if (maxResponseBytes <= 0) {
            return 2048;
        }
        return (int) Math.min(maxResponseBytes, Integer.MAX_VALUE);
    }

    private Path workspaceRoot() {
        Path workspaceRoot = securityProperties.workspaceRoot();
        if (workspaceRoot == null) {
            return null;
        }
        return workspaceRoot.normalize().toAbsolutePath();
    }

    private boolean containsShellControlOperator(String command) {
        return command.contains("&")
                || command.contains("|")
                || command.contains(";")
                || command.contains("<")
                || command.contains(">")
                || command.contains("\n")
                || command.contains("\r");
    }

    private List<String> allowedCommands() {
        List<String> allowedCommands = securityProperties.allowedCommands();
        return allowedCommands == null ? List.of() : allowedCommands;
    }

    private List<String> allowedDomains() {
        List<String> allowedDomains = securityProperties.http().allowedDomains();
        return allowedDomains == null ? List.of() : allowedDomains;
    }
}
