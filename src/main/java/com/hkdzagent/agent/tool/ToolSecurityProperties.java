package com.hkdzagent.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "agent.tools.security")
public class ToolSecurityProperties {

    private Path workspaceRoot;
    private List<String> allowedCommands;
    private Http http = new Http();

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public List<String> allowedCommands() {
        return allowedCommands;
    }

    public List<String> getAllowedCommands() {
        return allowedCommands;
    }

    public void setAllowedCommands(List<String> allowedCommands) {
        this.allowedCommands = allowedCommands;
    }

    public Http http() {
        return http;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public List<String> getHttpAllowedDomains() {
        return http.allowedDomains();
    }

    public void setHttpAllowedDomains(List<String> allowedDomains) {
        this.http.setAllowedDomains(allowedDomains);
    }

    public Duration getHttpConnectTimeout() {
        return http.connectTimeout();
    }

    public void setHttpConnectTimeout(Duration connectTimeout) {
        this.http.setConnectTimeout(connectTimeout);
    }

    public Duration getHttpReadTimeout() {
        return http.readTimeout();
    }

    public void setHttpReadTimeout(Duration readTimeout) {
        this.http.setReadTimeout(readTimeout);
    }

    public long getHttpMaxResponseBytes() {
        return http.maxResponseBytes();
    }

    public void setHttpMaxResponseBytes(long maxResponseBytes) {
        this.http.setMaxResponseBytes(maxResponseBytes);
    }

    public static class Http {

        private List<String> allowedDomains;
        private Duration connectTimeout;
        private Duration readTimeout;
        private long maxResponseBytes;

        public List<String> allowedDomains() {
            return allowedDomains;
        }

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains;
        }

        public Duration connectTimeout() {
            return connectTimeout;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration readTimeout() {
            return readTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public long maxResponseBytes() {
            return maxResponseBytes;
        }

        public long getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }
    }
}
