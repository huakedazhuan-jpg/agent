package com.hkdzagent.agent.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.tool-approval")
public class ToolConfirmationProperties {

    private String repository = "memory";
    private Duration ttl = Duration.ofMinutes(15);

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
}
