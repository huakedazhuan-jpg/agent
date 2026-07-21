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
        String traceId = UUID.randomUUID().toString();
        String message = userMessage == null ? "" : userMessage;
        traceRecorder.startTrace(owner, traceId, sessionId, message);
        AgentRun created = runtimeService.create(
                owner, sessionId, conversationId, traceId, message);
        String prefix = workerPrefix == null || workerPrefix.isBlank()
                ? "sync"
                : workerPrefix;
        runtimeExecutor.execute(
                created.runId(), prefix + "-" + UUID.randomUUID(), null);
        AgentRun result = runtimeService.find(created.runId());
        if (result == null) {
            throw new IllegalStateException("agent run disappeared after execution");
        }
        return result;
    }
}
