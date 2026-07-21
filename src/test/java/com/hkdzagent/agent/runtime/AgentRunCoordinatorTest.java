package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTrace;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRunCoordinatorTest {

    @Test
    void createsTraceAndRunThenReturnsPersistedExecutionState() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InMemoryAgentRunRepository runs = new InMemoryAgentRunRepository();
        AgentRuntimeService runtime = new AgentRuntimeService(
                runs, new AgentRuntimeProperties(), new ObjectMapper(), clock);
        InMemoryAgentTraceRepository traces = new InMemoryAgentTraceRepository();
        AgentTraceRecorder recorder = new AgentTraceRecorder(
                traces, new AgentTraceSanitizer(160));
        AgentRuntimeExecutor executor = mock(AgentRuntimeExecutor.class);
        doAnswer(invocation -> {
            String runId = invocation.getArgument(0);
            String workerId = invocation.getArgument(1);
            runtime.claim(runId, workerId);
            runtime.complete(runId, workerId, "coordinated answer");
            return null;
        }).when(executor).execute(anyString(), anyString(), isNull());
        AgentRunCoordinator coordinator = new AgentRunCoordinator(
                runtime, executor, recorder);
        ActorIdentity owner = ActorIdentity.user("coordinator-user");

        AgentRun result = coordinator.execute(
                owner,
                "session-1",
                "conversation-1",
                "hello",
                "http-chat"
        );

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("coordinated answer");
        assertThat(result.ownerKey()).isEqualTo(owner.key());
        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.conversationId()).isEqualTo("conversation-1");
        AgentTrace trace = traces.findByTraceIdAndOwner(result.traceId(), owner.key());
        assertThat(trace).isNotNull();
        assertThat(trace.userMessage()).isEqualTo("hello");
        verify(executor).execute(
                eq(result.runId()),
                org.mockito.ArgumentMatchers.startsWith("http-chat-"),
                isNull());
    }
}
