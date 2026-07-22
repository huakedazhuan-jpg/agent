package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.tool.ToolExecutionJournalEvidence;
import com.hkdzagent.agent.tool.ToolExecutionJournalRepository;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

public class AgentRunRecoveryService {

    private final AgentRuntimeService runtimeService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentFailureService failureService;
    private final AgentRunRecoveryClassifier classifier;
    private final ToolExecutionJournalRepository toolJournal;
    private final AgentRuntimeProperties properties;
    private final Executor executor;

    public AgentRunRecoveryService(
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentFailureService failureService,
            AgentRunRecoveryClassifier classifier,
            ToolExecutionJournalRepository toolJournal,
            AgentRuntimeProperties properties,
            Executor executor
    ) {
        this.runtimeService = runtimeService;
        this.runtimeExecutor = runtimeExecutor;
        this.failureService = failureService;
        this.classifier = classifier;
        this.toolJournal = toolJournal;
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
        ToolExecutionJournalEvidence toolEvidence = toolJournal.summarize(run.runId());
        AgentRunRecoveryDecision decision = classifier.classify(
                evidence, toolEvidence, properties.getMaxRecoveryAttempts());
        if (decision != AgentRunRecoveryDecision.SAFE_RESTART) {
            blockRecovery(run, workerId, evidence, toolEvidence, decision);
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
            ToolExecutionJournalEvidence toolEvidence,
            AgentRunRecoveryDecision decision
    ) {
        String reason = switch (decision) {
            case BLOCKED_TOOL_EXECUTION_UNCERTAIN ->
                    "automatic recovery blocked because a tool execution has no durable result";
            case BLOCKED_TOOL_CHECKPOINT_MISSING ->
                    "automatic recovery blocked because completed tools cannot be resumed without a durable model checkpoint";
            case BLOCKED_TOOL_JOURNAL_MISSING ->
                    "automatic recovery blocked because a tool started without journal evidence";
            case BLOCKED_ATTEMPTS -> "automatic recovery attempts exhausted";
            case SAFE_RESTART -> throw new IllegalArgumentException(
                    "safe restart must not be blocked");
        };
        runtimeService.appendWorkerEvent(
                run.runId(), workerId, run.leaseEpoch(),
                AgentRunEventType.RUN_RECOVERY_BLOCKED,
                Map.of(
                        "reason", decision.name(),
                        "recoveryAttempts", evidence.recoveryAttempts(),
                        "toolExecutionStarted", evidence.toolExecutionStarted(),
                        "journalStarted", toolEvidence.startedExecutions(),
                        "journalCompleted", toolEvidence.completedExecutions()));
        failureService.fail(
                run.runId(), workerId, run.leaseEpoch(), reason);
    }
}
