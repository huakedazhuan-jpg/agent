package com.hkdzagent.agent.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

public class AgentRunRecoveryService {

    private final AgentRuntimeService runtimeService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentFailureService failureService;
    private final AgentRunRecoveryClassifier classifier;
    private final AgentRuntimeProperties properties;
    private final Executor executor;

    public AgentRunRecoveryService(
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentFailureService failureService,
            AgentRunRecoveryClassifier classifier,
            AgentRuntimeProperties properties,
            Executor executor
    ) {
        this.runtimeService = runtimeService;
        this.runtimeExecutor = runtimeExecutor;
        this.failureService = failureService;
        this.classifier = classifier;
        this.properties = properties;
        this.executor = executor;
    }

    public int recoverExpiredRuns() {
        int claimedCount = 0;
        for (int index = 0; index < properties.getRecoveryBatchSize(); index++) {
            String workerId = "recovery-" + UUID.randomUUID();
            AgentRunClaim claim = runtimeService.claimNextExpired(workerId);
            if (claim == null) {
                break;
            }
            claimedCount++;
            processClaim(claim.run(), workerId);
        }
        return claimedCount;
    }

    private void processClaim(AgentRun run, String workerId) {
        AgentRunRecoveryEvidence evidence = runtimeService.recoveryEvidence(run.runId());
        AgentRunRecoveryDecision decision = classifier.classify(
                evidence, properties.getMaxRecoveryAttempts());
        if (decision != AgentRunRecoveryDecision.SAFE_RESTART) {
            blockRecovery(run, workerId, evidence, decision);
            return;
        }
        int attempt = evidence.recoveryAttempts() + 1;
        runtimeService.appendWorkerEvent(
                run.runId(), workerId, run.leaseEpoch(),
                AgentRunEventType.RUN_RECOVERY_STARTED,
                Map.of("attempt", attempt, "workerId", workerId));
        executor.execute(() -> runtimeExecutor.executeClaimed(run, workerId, null));
    }

    private void blockRecovery(
            AgentRun run,
            String workerId,
            AgentRunRecoveryEvidence evidence,
            AgentRunRecoveryDecision decision
    ) {
        String reason = decision == AgentRunRecoveryDecision.BLOCKED_TOOL_EFFECT
                ? "automatic recovery blocked because a tool side effect may have occurred"
                : "automatic recovery attempts exhausted";
        runtimeService.appendWorkerEvent(
                run.runId(), workerId, run.leaseEpoch(),
                AgentRunEventType.RUN_RECOVERY_BLOCKED,
                Map.of(
                        "reason", decision.name(),
                        "recoveryAttempts", evidence.recoveryAttempts(),
                        "toolExecutionStarted", evidence.toolExecutionStarted()));
        failureService.fail(
                run.runId(), workerId, run.leaseEpoch(), reason);
    }
}
