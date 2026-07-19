package com.hkdzagent.agent.runtime;

import org.springframework.scheduling.annotation.Scheduled;

public class AgentApprovalRecoveryScheduler {

    private final AgentApprovalOrchestrator orchestrator;

    public AgentApprovalRecoveryScheduler(AgentApprovalOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${agent.tool-approval.recovery-interval:15s}")
    public void recover() {
        orchestrator.recoverDecidedApprovals();
    }
}
