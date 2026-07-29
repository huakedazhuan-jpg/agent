package com.hkdzagent.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {

    private String repository = "memory";
    private int maxSteps = 5;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration heartbeatInterval = Duration.ofSeconds(10);
    private int eventReplayLimit = 500;
    private int recoveryBatchSize = 10;
    private int maxRecoveryAttempts = 3;

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

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getEventReplayLimit() {
        return eventReplayLimit;
    }

    public void setEventReplayLimit(int eventReplayLimit) {
        this.eventReplayLimit = eventReplayLimit;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public int getMaxRecoveryAttempts() {
        return maxRecoveryAttempts;
    }

    public void setMaxRecoveryAttempts(int maxRecoveryAttempts) {
        this.maxRecoveryAttempts = maxRecoveryAttempts;
    }
}
