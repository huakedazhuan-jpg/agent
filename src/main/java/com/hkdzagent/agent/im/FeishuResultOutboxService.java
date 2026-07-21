package com.hkdzagent.agent.im;

import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunStatus;
import com.hkdzagent.agent.security.ActorIdentity;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class FeishuResultOutboxService {

    private final FeishuResultOutboxRepository repository;
    private final Clock clock;

    public FeishuResultOutboxService(
            FeishuResultOutboxRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    public boolean enqueueCompletedRun(AgentRun run) {
        if (run == null || run.status() != AgentRunStatus.COMPLETED) {
            return false;
        }
        ActorIdentity owner = new ActorIdentity(run.ownerKey());
        if (!"feishu".equals(owner.namespace())) {
            return false;
        }
        Instant now = clock.instant();
        return repository.enqueue(new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), run.runId(), owner.subject(),
                run.finalAnswer() == null ? "" : run.finalAnswer(),
                FeishuResultOutboxMessage.Type.FINAL_RESULT,
                "run:" + run.runId() + ":final-result",
                FeishuResultOutboxMessage.Status.PENDING,
                now, null, null, null, null, 0, null));
    }

    public boolean enqueueApprovalRequired(AgentRun run) {
        if (run == null
                || run.status() != AgentRunStatus.WAITING_APPROVAL
                || run.pendingApprovalId() == null
                || run.pendingApprovalId().isBlank()) {
            return false;
        }
        ActorIdentity owner = new ActorIdentity(run.ownerKey());
        if (!"feishu".equals(owner.namespace())) {
            return false;
        }
        Instant now = clock.instant();
        return repository.enqueue(new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), run.runId(), owner.subject(),
                "Tool approval required. runId=" + run.runId(),
                FeishuResultOutboxMessage.Type.APPROVAL_REQUIRED,
                "run:" + run.runId() + ":approval:" + run.pendingApprovalId(),
                FeishuResultOutboxMessage.Status.PENDING,
                now, null, null, null, null, 0, null));
    }

    public boolean enqueueFailedRun(AgentRun run) {
        if (run == null || run.status() != AgentRunStatus.FAILED) {
            return false;
        }
        ActorIdentity owner = new ActorIdentity(run.ownerKey());
        if (!"feishu".equals(owner.namespace())) {
            return false;
        }
        Instant now = clock.instant();
        return repository.enqueue(new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), run.runId(), owner.subject(),
                "抱歉，Agent 执行失败，请稍后重试。runId=" + run.runId(),
                FeishuResultOutboxMessage.Type.RUN_FAILED,
                "run:" + run.runId() + ":run-failed",
                FeishuResultOutboxMessage.Status.PENDING,
                now, null, null, null, null, 0, null));
    }
}
