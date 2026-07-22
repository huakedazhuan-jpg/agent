package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(
        String runId,
        String ownerKey,
        String sessionId,
        String conversationId,
        String traceId,
        String userMessage,
        AgentRunStatus status,
        int currentStep,
        int maxSteps,
        long version,
        long lastEventSequence,
        String checkpointJson,
        String pendingApprovalId,
        String finalAnswer,
        String errorMessage,
        String leaseOwner,
        Instant leaseExpiresAt,
        long leaseEpoch,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {

    public AgentRun {
        requireUuid(runId, "runId");
        new ActorIdentity(ownerKey);
        requireText(sessionId, "sessionId");
        requireText(conversationId, "conversationId");
        requireText(traceId, "traceId");
        if (status == null) {
            throw new IllegalArgumentException("run status must not be null");
        }
        if (currentStep < 0) {
            throw new IllegalArgumentException("currentStep must not be negative");
        }
        if (maxSteps < 1 || currentStep > maxSteps) {
            throw new IllegalArgumentException("run step bounds are invalid");
        }
        if (version < 0 || lastEventSequence < 0 || leaseEpoch < 0) {
            throw new IllegalArgumentException("run version, event sequence, and lease epoch must not be negative");
        }
        checkpointJson = checkpointJson == null || checkpointJson.isBlank() ? "{}" : checkpointJson;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (status.terminal() && completedAt == null) {
            throw new IllegalArgumentException("terminal run must have completedAt");
        }
        if (!status.terminal() && completedAt != null) {
            throw new IllegalArgumentException("non-terminal run must not have completedAt");
        }
        if (status == AgentRunStatus.WAITING_APPROVAL
                && (pendingApprovalId == null || pendingApprovalId.isBlank())) {
            throw new IllegalArgumentException("waiting run must reference a pending approval");
        }
        if (pendingApprovalId != null) {
            requireUuid(pendingApprovalId, "pendingApprovalId");
        }
        if ((leaseOwner == null) != (leaseExpiresAt == null)) {
            throw new IllegalArgumentException("lease owner and expiry must be set together");
        }
        if ((status == AgentRunStatus.WAITING_APPROVAL || status.terminal()) && leaseOwner != null) {
            throw new IllegalArgumentException("waiting or terminal run must not retain a worker lease");
        }
    }

    public static AgentRun created(
            String runId,
            ActorIdentity owner,
            String sessionId,
            String conversationId,
            String traceId,
            String userMessage,
            int maxSteps,
            Instant now
    ) {
        return new AgentRun(
                runId,
                owner.key(),
                sessionId,
                conversationId,
                traceId,
                userMessage == null ? "" : userMessage,
                AgentRunStatus.CREATED,
                0,
                maxSteps,
                0,
                0,
                "{}",
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now,
                null
        );
    }

    public boolean hasActiveLease(Instant now) {
        return leaseOwner != null && leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
    }

    public AgentRun claim(String workerId, Instant now, Instant leaseExpiry) {
        requireText(workerId, "workerId");
        if (status != AgentRunStatus.CREATED && status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException("run is not claimable from status " + status);
        }
        if (leaseExpiry == null || !leaseExpiry.isAfter(now)) {
            throw new IllegalArgumentException("lease expiry must be after claim time");
        }
        if (hasActiveLease(now) && !workerId.equals(leaseOwner)) {
            throw new IllegalStateException("run is leased by another worker");
        }
        return copy(
                AgentRunStatus.RUNNING,
                currentStep,
                version + 1,
                lastEventSequence,
                checkpointJson,
                null,
                finalAnswer,
                errorMessage,
                workerId,
                leaseExpiry,
                leaseEpoch + 1,
                now,
                null
        );
    }

    public boolean holdsLease(String workerId, long requiredLeaseEpoch, Instant now) {
        return status == AgentRunStatus.RUNNING
                && workerId != null
                && workerId.equals(leaseOwner)
                && requiredLeaseEpoch == leaseEpoch
                && hasActiveLease(now);
    }

    public AgentRun renewLease(
            String workerId,
            long requiredLeaseEpoch,
            Instant now,
            Instant renewedLeaseExpiry
    ) {
        if (!holdsLease(workerId, requiredLeaseEpoch, now)) {
            throw new IllegalStateException("worker does not hold the current run lease fence");
        }
        if (renewedLeaseExpiry == null || !renewedLeaseExpiry.isAfter(now)) {
            throw new IllegalArgumentException("renewed lease expiry must be after heartbeat time");
        }
        return copy(
                status, currentStep, version + 1, lastEventSequence,
                checkpointJson, pendingApprovalId, finalAnswer, errorMessage,
                leaseOwner, renewedLeaseExpiry, now, null
        );
    }

    public AgentRun advance(
            int step, String checkpoint, String workerId, long requiredLeaseEpoch, Instant now
    ) {
        return advance(step, checkpoint, workerId, requiredLeaseEpoch, now, leaseExpiresAt);
    }

    public AgentRun advance(
            int step,
            String checkpoint,
            String workerId,
            long requiredLeaseEpoch,
            Instant now,
            Instant renewedLeaseExpiry
    ) {
        requireWorkerLease(workerId, requiredLeaseEpoch, now);
        if (step < currentStep || step > maxSteps) {
            throw new IllegalArgumentException("runtime step cannot move backwards or exceed maxSteps");
        }
        if (renewedLeaseExpiry == null || !renewedLeaseExpiry.isAfter(now)) {
            throw new IllegalArgumentException("renewed lease expiry must be after checkpoint time");
        }
        return copy(
                status,
                step,
                version + 1,
                lastEventSequence,
                checkpoint,
                pendingApprovalId,
                finalAnswer,
                errorMessage,
                leaseOwner,
                renewedLeaseExpiry,
                now,
                null
        );
    }

    public AgentRun complete(String answer, String workerId, long requiredLeaseEpoch, Instant now) {
        requireWorkerLease(workerId, requiredLeaseEpoch, now);
        return copy(
                AgentRunStatus.COMPLETED,
                currentStep,
                version + 1,
                lastEventSequence,
                checkpointJson,
                null,
                answer == null ? "" : answer,
                null,
                null,
                null,
                now,
                now
        );
    }

    public AgentRun waitForApproval(
            String approvalId,
            String checkpoint,
            String workerId,
            long requiredLeaseEpoch,
            Instant now
    ) {
        requireWorkerLease(workerId, requiredLeaseEpoch, now);
        requireUuid(approvalId, "approvalId");
        return copy(
                AgentRunStatus.WAITING_APPROVAL, currentStep, version + 1, lastEventSequence,
                checkpoint, approvalId, null, null, null, null, now, null
        );
    }

    public AgentRun resumeApproval(
            String approvalId,
            String workerId,
            Instant now,
            Instant leaseExpiry
    ) {
        requireText(workerId, "workerId");
        if (status != AgentRunStatus.WAITING_APPROVAL
                || approvalId == null
                || !approvalId.equals(pendingApprovalId)) {
            throw new IllegalStateException("run is not waiting for this approval");
        }
        if (leaseExpiry == null || !leaseExpiry.isAfter(now)) {
            throw new IllegalArgumentException("lease expiry must be after resume time");
        }
        return copy(
                AgentRunStatus.RUNNING, currentStep, version + 1, lastEventSequence,
                checkpointJson, null, null, null, workerId, leaseExpiry,
                leaseEpoch + 1, now, null
        );
    }

    public AgentRun rejectApproval(String approvalId, String reason, Instant now) {
        if (status != AgentRunStatus.WAITING_APPROVAL
                || approvalId == null
                || !approvalId.equals(pendingApprovalId)) {
            throw new IllegalStateException("run is not waiting for this approval");
        }
        return copy(
                AgentRunStatus.FAILED, currentStep, version + 1, lastEventSequence,
                checkpointJson, null, null,
                reason == null ? "tool approval rejected" : reason,
                null, null, now, now
        );
    }

    public AgentRun fail(String error, String workerId, long requiredLeaseEpoch, Instant now) {
        requireWorkerLease(workerId, requiredLeaseEpoch, now);
        return copy(
                AgentRunStatus.FAILED,
                currentStep,
                version + 1,
                lastEventSequence,
                checkpointJson,
                null,
                null,
                error == null ? "agent execution failed" : error,
                null,
                null,
                now,
                now
        );
    }

    public AgentRun cancel(Instant now) {
        if (!status.canTransitionTo(AgentRunStatus.CANCELLED)) {
            throw new IllegalStateException("run cannot be cancelled from status " + status);
        }
        return copy(
                AgentRunStatus.CANCELLED,
                currentStep,
                version + 1,
                lastEventSequence,
                checkpointJson,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    public AgentRun withEventSequence(long sequence, Instant now) {
        if (sequence != lastEventSequence + 1) {
            throw new IllegalArgumentException("runtime event sequence must be contiguous");
        }
        return copy(
                status,
                currentStep,
                version,
                sequence,
                checkpointJson,
                pendingApprovalId,
                finalAnswer,
                errorMessage,
                leaseOwner,
                leaseExpiresAt,
                now,
                completedAt
        );
    }

    AgentRun synchronizeEventSequence(long sequence) {
        if (sequence < lastEventSequence) {
            throw new IllegalArgumentException("persisted event sequence cannot move backwards");
        }
        return copy(
                status,
                currentStep,
                version,
                sequence,
                checkpointJson,
                pendingApprovalId,
                finalAnswer,
                errorMessage,
                leaseOwner,
                leaseExpiresAt,
                updatedAt,
                completedAt
        );
    }

    private void requireWorkerLease(String workerId, long requiredLeaseEpoch, Instant now) {
        if (!holdsLease(workerId, requiredLeaseEpoch, now)) {
            throw new AgentRunLeaseLostException("worker does not hold the current run lease fence");
        }
    }

    private AgentRun copy(
            AgentRunStatus nextStatus,
            int nextStep,
            long nextVersion,
            long nextEventSequence,
            String nextCheckpoint,
            String nextPendingApprovalId,
            String nextFinalAnswer,
            String nextErrorMessage,
            String nextLeaseOwner,
            Instant nextLeaseExpiresAt,
            Instant nextUpdatedAt,
            Instant nextCompletedAt
    ) {
        return copy(
                nextStatus, nextStep, nextVersion, nextEventSequence, nextCheckpoint,
                nextPendingApprovalId, nextFinalAnswer, nextErrorMessage,
                nextLeaseOwner, nextLeaseExpiresAt, leaseEpoch, nextUpdatedAt, nextCompletedAt
        );
    }

    private AgentRun copy(
            AgentRunStatus nextStatus,
            int nextStep,
            long nextVersion,
            long nextEventSequence,
            String nextCheckpoint,
            String nextPendingApprovalId,
            String nextFinalAnswer,
            String nextErrorMessage,
            String nextLeaseOwner,
            Instant nextLeaseExpiresAt,
            long nextLeaseEpoch,
            Instant nextUpdatedAt,
            Instant nextCompletedAt
    ) {
        return new AgentRun(
                runId,
                ownerKey,
                sessionId,
                conversationId,
                traceId,
                userMessage,
                nextStatus,
                nextStep,
                maxSteps,
                nextVersion,
                nextEventSequence,
                nextCheckpoint,
                nextPendingApprovalId,
                nextFinalAnswer,
                nextErrorMessage,
                nextLeaseOwner,
                nextLeaseExpiresAt,
                nextLeaseEpoch,
                createdAt,
                nextUpdatedAt,
                nextCompletedAt
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireUuid(String value, String field) {
        requireText(value, field);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }
}
