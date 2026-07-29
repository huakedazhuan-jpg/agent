package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.trace.AgentTraceRecorder;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class AgentApprovalOrchestrator {

    private final ToolConfirmationService confirmationService;
    private final ToolConfirmationRepository confirmationRepository;
    private final AgentRuntimeService runtimeService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentTraceRecorder traceRecorder;
    private final AgentFailureService failureService;
    private final Executor executor;

    public AgentApprovalOrchestrator(
            ToolConfirmationService confirmationService,
            ToolConfirmationRepository confirmationRepository,
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentTraceRecorder traceRecorder,
            Executor executor
    ) {
        this(confirmationService, confirmationRepository, runtimeService,
                runtimeExecutor, traceRecorder, null, executor);
    }

    public AgentApprovalOrchestrator(
            ToolConfirmationService confirmationService,
            ToolConfirmationRepository confirmationRepository,
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentTraceRecorder traceRecorder,
            AgentFailureService failureService,
            Executor executor
    ) {
        this.confirmationService = confirmationService;
        this.confirmationRepository = confirmationRepository;
        this.runtimeService = runtimeService;
        this.runtimeExecutor = runtimeExecutor;
        this.traceRecorder = traceRecorder;
        this.failureService = failureService;
        this.executor = executor;
    }

    public ToolConfirmation approve(String confirmationId) {
        ToolConfirmation confirmation = confirmationService.findById(confirmationId);
        ToolConfirmation approved = decideWhileWaiting(
                confirmation, () -> confirmationService.approve(confirmationId));
        scheduleResume(approved);
        return approved;
    }

    public ToolConfirmation reject(String confirmationId, String reason) {
        ToolConfirmation confirmation = confirmationService.findById(confirmationId);
        ToolConfirmation rejected = decideWhileWaiting(
                confirmation, () -> confirmationService.reject(confirmationId, reason));
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
        String workerId = "approval-" + UUID.randomUUID();
        try {
            runtimeExecutor.resumeApproved(
                    run.runId(), confirmation.id(), workerId, null);
        } catch (IllegalStateException exception) {
            AgentRun latest = runtimeService.find(run.runId());
            if (latest == null || latest.status().terminal()) {
                return;
            }
            if (latest.status() == AgentRunStatus.RUNNING
                    && !workerId.equals(latest.leaseOwner())) {
                return;
            }
            throw exception;
        }
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
            if (failureService == null) {
                runtimeService.rejectApproval(run.runId(), confirmation.id(), reason);
                runtimeService.appendEvent(run.runId(), AgentRunEventType.RUN_FAILED,
                        java.util.Map.of("error", reason, "approvalId", confirmation.id()));
            } else {
                failureService.rejectApproval(
                        run.runId(), confirmation.id(), reason);
            }
            traceRecorder.recordError(run.traceId(), run.currentStep(), reason, null);
            traceRecorder.finishTrace(run.traceId(), "FAILED");
        } catch (IllegalStateException exception) {
            AgentRun latest = runtimeService.find(run.runId());
            if (isWaitingFor(latest, confirmation.id())) {
                throw exception;
            }
            // Another instance already applied this durable decision.
        }
    }

    private boolean isWaitingFor(AgentRun run, String approvalId) {
        return run != null
                && run.status() == AgentRunStatus.WAITING_APPROVAL
                && approvalId.equals(run.pendingApprovalId());
    }

    private ToolConfirmation decideWhileWaiting(
            ToolConfirmation confirmation,
            Supplier<ToolConfirmation> decision
    ) {
        if (confirmation == null || confirmation.runId() == null) {
            return decision.get();
        }
        ToolConfirmation decided = runtimeService.decideWaitingApproval(
                confirmation.runId(), confirmation.id(), decision);
        if (decided != null) {
            return decided;
        }
        AgentRun run = runtimeService.find(confirmation.runId());
        String runStatus = run == null ? "NOT_FOUND" : run.status().name();
        throw new AgentApprovalConflictException(
                "agent run is no longer waiting for approval "
                        + confirmation.id() + " (" + runStatus + ")");
    }
}
