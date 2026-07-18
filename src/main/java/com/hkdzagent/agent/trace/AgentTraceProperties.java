package com.hkdzagent.agent.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.trace")
public class AgentTraceProperties {

    private String repository = "memory";

    public String repository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }
}
