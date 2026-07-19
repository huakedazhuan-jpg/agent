package com.hkdzagent.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {

    private String repository = "memory";
    private int maxSteps = 5;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int eventReplayLimit = 500;

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getEventReplayLimit() {
        return eventReplayLimit;
    }

    public void setEventReplayLimit(int eventReplayLimit) {
        this.eventReplayLimit = eventReplayLimit;
    }
}
