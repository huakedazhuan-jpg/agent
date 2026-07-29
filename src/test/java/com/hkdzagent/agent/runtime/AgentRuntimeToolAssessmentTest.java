package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.console.InMemoryToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolPipelineResult;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeToolAssessmentTest {

    @Test
    void runtimeAssessesModelToolCallWithDurableInvocationIdentityBeforePausing() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(), new AgentRuntimeProperties(), new ObjectMapper(), clock);
        AgentTraceRecorder recorder = new AgentTraceRecorder(
                new InMemoryAgentTraceRepository(), sanitizer);
        InMemoryToolConfirmationRepository confirmations =
                new InMemoryToolConfirmationRepository();
        ToolConfirmationService confirmationService = new ToolConfirmationService(
                confirmations, sanitizer, Duration.ofMinutes(15), clock);
        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        when(pipeline.assess(
                any(ToolInvocationContext.class),
                eq("commandExecuteTool"),
                eq("{\"command\":\"mvn test\"}")
        )).thenReturn(new ToolPipelineResult(
                ToolPipelineResult.Status.APPROVAL_REQUIRED,
                "commandExecuteTool",
                "1.0.0",
                "a".repeat(64),
                "{\"command\":\"mvn test\"}",
                null,
                "tool policy always requires approval"
        ));

        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    observer.modelStarted(1);
                    observer.toolCallRequested(
                            1, "call-runtime-1", "commandExecuteTool",
                            "{\"command\":\"mvn test\"}");
                    assertThat(observer.requiresApproval(
                            1, "call-runtime-1", "commandExecuteTool",
                            "{\"command\":\"mvn test\"}"))
                            .isTrue();
                    observer.approvalRequired(
                            1, "call-runtime-1", "commandExecuteTool",
                            "{\"command\":\"mvn test\"}",
                            "{\"schemaVersion\":1,\"toolCall\":{\"id\":\"call-runtime-1\","
                                    + "\"function\":{\"name\":\"commandExecuteTool\","
                                    + "\"arguments\":\"{\\\"command\\\":\\\"mvn test\\\"}\"}}}");
                    return new AgentLoopResult(
                            AgentLoopResult.Status.WAITING_APPROVAL,
                            invocation.getArgument(2),
                            "waiting",
                            List.of()
                    );
                });

        AgentRuntimeExecutor executor = new AgentRuntimeExecutor(
                runtime,
                llm,
                recorder,
                sanitizer,
                new AgentApprovalPauseService(
                        confirmationService, runtime, sanitizer, new ObjectMapper()),
                new ToolConfirmationProperties(),
                pipeline
        );
        AgentRun run = runtime.create(
                ActorIdentity.user("phase-5-user"),
                "phase-5-session",
                "phase-5-conversation",
                "phase-5-trace",
                "run tests"
        );
        recorder.startTrace(
                ActorIdentity.user("phase-5-user"),
                run.traceId(),
                run.sessionId(),
                run.userMessage()
        );

        executor.execute(run.runId(), "phase-5-worker", null);

        ArgumentCaptor<ToolInvocationContext> context =
                ArgumentCaptor.forClass(ToolInvocationContext.class);
        verify(pipeline).assess(
                context.capture(),
                eq("commandExecuteTool"),
                eq("{\"command\":\"mvn test\"}")
        );
        assertThat(context.getValue().ownerKey()).isEqualTo("user:phase-5-user");
        assertThat(context.getValue().runId()).isEqualTo(run.runId());
        assertThat(context.getValue().traceId()).isEqualTo("phase-5-trace");
        assertThat(context.getValue().toolCallId()).isEqualTo("call-runtime-1");
        assertThat(context.getValue().authorities()).isEmpty();
        assertThat(runtime.find(run.runId()).status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        ToolConfirmation pending = confirmations.findByStatus(
                ToolConfirmation.Status.PENDING, 10).get(0);
        assertThat(pending.ownerKey()).isEqualTo("user:phase-5-user");
        assertThat(pending.runId()).isEqualTo(run.runId());
        assertThat(pending.traceId()).isEqualTo("phase-5-trace");
        assertThat(pending.toolName()).isEqualTo("commandExecuteTool");
        assertThat(pending.toolVersion()).isEqualTo("1.0.0");
        assertThat(pending.toolCallId()).isEqualTo("call-runtime-1");
        assertThat(pending.argumentsHash()).isEqualTo("a".repeat(64));
        assertThat(pending.argumentsPreview()).isEqualTo("{\"command\":\"mvn test\"}");
        assertThat(runtime.find(run.runId()).checkpointJson()).contains("call-runtime-1");
    }
}
