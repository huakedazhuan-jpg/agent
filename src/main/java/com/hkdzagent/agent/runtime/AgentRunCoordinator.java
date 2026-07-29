package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;

import java.util.UUID;

public class AgentRunCoordinator {

    private final AgentRuntimeService runtimeService;
    private final AgentRuntimeExecutor runtimeExecutor;
    private final AgentTraceRecorder traceRecorder;

    public AgentRunCoordinator(
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentTraceRecorder traceRecorder
    ) {
        this.runtimeService = runtimeService;
        this.runtimeExecutor = runtimeExecutor;
        this.traceRecorder = traceRecorder;
    }

    public AgentRun execute(
            ActorIdentity owner,
            String sessionId,
            String conversationId,
            String userMessage,
            String workerPrefix
    ) {
        AgentRun created = create(owner, sessionId, conversationId, userMessage);
        return executeCreated(created.runId(), workerPrefix);
    }

    public AgentRun create(
            ActorIdentity owner,
            String sessionId,
            String conversationId,
            String userMessage
    ) {
        String traceId = UUID.randomUUID().toString();
        String message = userMessage == null ? "" : userMessage;
        traceRecorder.startTrace(owner, traceId, sessionId, message);
        return runtimeService.create(
                owner, sessionId, conversationId, traceId, message);
    }

    public AgentRun executeCreated(String runId, String workerPrefix) {
        AgentRun current = runtimeService.find(runId);
        if (current == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        if (current.status() != AgentRunStatus.CREATED) {
            return current;
        }
        String prefix = workerPrefix == null || workerPrefix.isBlank()
                ? "sync"
                : workerPrefix;
        runtimeExecutor.execute(
                runId, prefix + "-" + UUID.randomUUID(), null);
        AgentRun result = runtimeService.find(runId);
        if (result == null) {
            throw new IllegalStateException("agent run disappeared after execution");
        }
        return result;
    }

    public AgentRun find(String runId) {
        return runtimeService.find(runId);
    }
}
