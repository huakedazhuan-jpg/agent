package com.hkdzagent.agent.runtime;

import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;

public class AgentRunLeaseHeartbeatFactory {

    private final AgentRuntimeService runtimeService;
    private final TaskScheduler scheduler;
    private final Duration interval;

    public AgentRunLeaseHeartbeatFactory(
            AgentRuntimeService runtimeService,
            TaskScheduler scheduler,
            Duration interval
    ) {
        this.runtimeService = runtimeService;
        this.scheduler = scheduler;
        this.interval = interval;
    }

    AgentRunLeaseHeartbeat start(String runId, String workerId, long leaseEpoch) {
        return new AgentRunLeaseHeartbeat(
                runtimeService, scheduler, interval, runId, workerId, leaseEpoch);
    }
}
