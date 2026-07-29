package com.hkdzagent.agent.runtime;

import org.springframework.scheduling.annotation.Scheduled;

public class AgentRunRecoveryScheduler {

    private final AgentRunRecoveryService recoveryService;

    public AgentRunRecoveryScheduler(AgentRunRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${agent.runtime.recovery-interval:15s}")
    public void recover() {
        recoveryService.recoverExpiredRuns();
    }
}
