package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.trace.AgentTraceRecorder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

public class AgentApprovalOrchestrator {

    private final ToolConfirmationService confirmationService;
    private final ToolConfirmationRepository confirmationRepository;
    private final AgentRuntimeService runtimeService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentTraceRecorder traceRecorder;
    private final Executor executor;

    public AgentApprovalOrchestrator(
            ToolConfirmationService confirmationService,
            ToolConfirmationRepository confirmationRepository,
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentTraceRecorder traceRecorder,
            Executor executor
    ) {
        this.confirmationService = confirmationService;
        this.confirmationRepository = confirmationRepository;
        this.runtimeService = runtimeService;
        this.runtimeExecutor = runtimeExecutor;
        this.traceRecorder = traceRecorder;
        this.executor = executor;
    }

    public ToolConfirmation approve(String confirmationId) {
        ToolConfirmation approved = confirmationService.approve(confirmationId);
        scheduleResume(approved);
        return approved;
    }

    public ToolConfirmation reject(String confirmationId, String reason) {
        ToolConfirmation rejected = confirmationService.reject(confirmationId, reason);
        applyRejection(rejected);
        return rejected;
    }

    public void recoverDecidedApprovals() {
        confirmationService.expirePending();
        confirmationRepository.findByStatus(ToolConfirmation.Status.APPROVED, 100)
                .forEach(this::scheduleResume);
        confirmationRepository.findByStatus(ToolConfirmation.Status.REJECTED, 100)
                .forEach(this::applyRejection);
        confirmationRepository.findByStatus(ToolConfirmation.Status.EXPIRED, 100)
                .forEach(this::applyRejection);
    }

    private void scheduleResume(ToolConfirmation confirmation) {
        if (confirmation.runId() == null) {
            return;
        }
        executor.execute(() -> resumeIfWaiting(confirmation));
    }

    private void resumeIfWaiting(ToolConfirmation confirmation) {
        AgentRun run = runtimeService.find(confirmation.runId());
        if (!isWaitingFor(run, confirmation.id())) {
            return;
        }
        runtimeExecutor.resumeApproved(
                run.runId(), confirmation.id(), "approval-" + UUID.randomUUID(), null);
    }

    private void applyRejection(ToolConfirmation confirmation) {
        if (confirmation.runId() == null) {
            return;
        }
        AgentRun run = runtimeService.find(confirmation.runId());
        if (!isWaitingFor(run, confirmation.id())) {
            return;
        }
        String reason = confirmation.decisionReason() == null
                ? "tool approval rejected"
                : confirmation.decisionReason();
        try {
            runtimeService.rejectApproval(run.runId(), confirmation.id(), reason);
            runtimeService.appendEvent(run.runId(), AgentRunEventType.RUN_FAILED, Map.of(
                    "error", reason,
                    "approvalId", confirmation.id()
            ));
            traceRecorder.recordError(run.traceId(), run.currentStep(), reason, null);
            traceRecorder.finishTrace(run.traceId(), "FAILED");
        } catch (IllegalStateException ignored) {
            // Another instance already applied this durable decision.
        }
    }

    private boolean isWaitingFor(AgentRun run, String approvalId) {
        return run != null
                && run.status() == AgentRunStatus.WAITING_APPROVAL
                && approvalId.equals(run.pendingApprovalId());
    }
}
