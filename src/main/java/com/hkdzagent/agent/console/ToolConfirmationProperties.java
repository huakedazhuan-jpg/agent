package com.hkdzagent.agent.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "agent.tool-approval")
public class ToolConfirmationProperties {

    private String repository = "memory";
    private Duration ttl = Duration.ofMinutes(15);
    private List<String> requiredTools = List.of("fileOperationTool", "commandExecuteTool");

    public String repository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public Duration ttl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public List<String> requiredTools() {
        return requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }

    public List<String> getRequiredTools() {
        return requiredTools();
    }

    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools;
    }

    public boolean requiresApproval(String toolName) {
        return requiredTools().contains(toolName);
    }
}
