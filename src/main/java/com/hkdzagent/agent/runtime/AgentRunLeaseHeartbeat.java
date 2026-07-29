package com.hkdzagent.agent.runtime;

import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class AgentRunLeaseHeartbeat implements AutoCloseable {

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<AgentRunLeaseLostException> lost = new AtomicReference<>();
    private final ScheduledFuture<?> future;

    AgentRunLeaseHeartbeat(
            AgentRuntimeService runtimeService,
            TaskScheduler scheduler,
            Duration interval,
            String runId,
            String workerId,
            long leaseEpoch
    ) {
        future = scheduler.scheduleAtFixedRate(() -> {
            if (closed.get() || lost.get() != null) {
                return;
            }
            try {
                runtimeService.renewLease(runId, workerId, leaseEpoch);
            } catch (RuntimeException exception) {
                AgentRunLeaseLostException leaseLost = exception instanceof AgentRunLeaseLostException typed
                        ? typed
                        : new AgentRunLeaseLostException(
                                "agent run lease heartbeat failed for run " + runId,
                                exception);
                lost.compareAndSet(null, leaseLost);
            }
        }, interval);
    }

    void assertActive() {
        AgentRunLeaseLostException exception = lost.get();
        if (exception != null) {
            throw exception;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        future.cancel(false);
    }
}
