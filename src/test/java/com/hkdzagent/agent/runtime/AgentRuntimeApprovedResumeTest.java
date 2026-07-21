package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.InMemoryToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.loop.AgentObservation;
import com.hkdzagent.agent.im.FeishuResultOutboxMessage;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.im.InMemoryFeishuResultOutboxRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.AgentTool;
import com.hkdzagent.agent.tool.AgentToolRegistry;
import com.hkdzagent.agent.tool.ToolAccessPolicy;
import com.hkdzagent.agent.tool.ToolApprovalPolicy;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.tool.ToolInvocationValidator;
import com.hkdzagent.agent.tool.ToolMetadata;
import com.hkdzagent.agent.tool.ToolPolicyEngine;
import com.hkdzagent.agent.tool.ToolResult;
import com.hkdzagent.agent.tool.ToolRetryPolicy;
import com.hkdzagent.agent.tool.ToolRiskLevel;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeApprovedResumeTest {

    @Test
    void approvedRunExecutesBoundToolOnceAndDuplicateResumeCannotExecuteAgain() {
        ObjectMapper objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(),
                new AgentRuntimeProperties(), objectMapper, clock);
        AgentTraceRecorder recorder = new AgentTraceRecorder(
                new InMemoryAgentTraceRepository(), sanitizer);
        InMemoryToolConfirmationRepository confirmationRepository =
                new InMemoryToolConfirmationRepository();
        ToolConfirmationService confirmationService = new ToolConfirmationService(
                confirmationRepository, sanitizer, Duration.ofMinutes(15), clock);
        CountingTool tool = new CountingTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolInvocationValidator(
                        new AgentToolRegistry(List.of(tool)), objectMapper, 160),
                new ToolPolicyEngine(
                        ToolAccessPolicy.allowAuthenticated(), invocation -> false));
        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(
                anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    String arguments = "{\"value\":\"approved payload\"}";
                    observer.modelStarted(1);
                    observer.toolCallRequested(
                            1, "call-runtime-approved", "writeFile", arguments);
                    assertThat(observer.requiresApproval(
                            1, "call-runtime-approved", "writeFile", arguments)).isTrue();
                    observer.approvalRequired(
                            1, "call-runtime-approved", "writeFile", arguments,
                            "{\"schemaVersion\":1,\"traceId\":\"trace-approved\",\"step\":1,"
                                    + "\"toolCall\":{\"id\":\"call-runtime-approved\","
                                    + "\"function\":{\"name\":\"writeFile\","
                                    + "\"arguments\":\"{\\\"value\\\":\\\"approved payload\\\"}\"}}}");
                    return new AgentLoopResult(
                            AgentLoopResult.Status.WAITING_APPROVAL,
                            "trace-approved", "waiting", List.of());
                });
        when(llm.resumeWithApprovedTool(
                anyString(), any(AgentObservation.class), any(AgentExecutionObserver.class)))
                .thenReturn(new AgentLoopResult(
                        AgentLoopResult.Status.COMPLETED,
                        "trace-approved", "completed after approval", List.of()));

        ApprovedToolExecutionService approvedExecutionService =
                new ApprovedToolExecutionService(
                        confirmationService, pipeline, objectMapper);
        InMemoryFeishuResultOutboxRepository outbox =
                new InMemoryFeishuResultOutboxRepository();
        AgentCompletionService completionService = new AgentCompletionService(
                runtime, new FeishuResultOutboxService(outbox, clock));
        AgentRuntimeExecutor executor = new AgentRuntimeExecutor(
                runtime, llm, recorder, sanitizer,
                new AgentApprovalPauseService(
                        confirmationService, runtime, sanitizer, objectMapper),
                new ToolConfirmationProperties(), pipeline,
                approvedExecutionService, completionService);
        AgentRun run = runtime.create(
                ActorIdentity.feishu("approved-open"),
                "session-approved", "conversation-approved",
                "trace-approved", "write approved payload");
        recorder.startTrace(
                ActorIdentity.feishu("approved-open"), run.traceId(),
                run.sessionId(), run.userMessage());

        executor.execute(run.runId(), "initial-worker", null);
        ToolConfirmation pending = confirmationRepository.findByStatus(
                ToolConfirmation.Status.PENDING, 10).get(0);
        confirmationService.approve(pending.id());
        executor.resumeApproved(run.runId(), pending.id(), "approval-worker", null);

        AgentRun completed = runtime.find(run.runId());
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.finalAnswer()).isEqualTo("completed after approval");
        assertThat(tool.executions()).isOne();
        FeishuResultOutboxMessage queued = outbox.findByDeduplicationKey(
                "run:" + run.runId() + ":final-result");
        assertThat(queued.status()).isEqualTo(FeishuResultOutboxMessage.Status.PENDING);
        assertThat(queued.openId()).isEqualTo("approved-open");
        assertThat(queued.text()).isEqualTo("completed after approval");
        ArgumentCaptor<AgentObservation> observation =
                ArgumentCaptor.forClass(AgentObservation.class);
        verify(llm).resumeWithApprovedTool(
                anyString(), observation.capture(), any(AgentExecutionObserver.class));
        assertThat(observation.getValue().content()).isEqualTo("approved payload");

        assertThatIllegalStateException().isThrownBy(() -> executor.resumeApproved(
                run.runId(), pending.id(), "duplicate-worker", null));
        assertThat(tool.executions()).isOne();
    }

    private record Input(String value) {
    }

    private static final class CountingTool implements AgentTool<Input, ToolResult> {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "writeFile", "1.0.0",
                    "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"],\"additionalProperties\":false}",
                    ToolRiskLevel.HIGH, ToolApprovalPolicy.ALWAYS,
                    Duration.ofSeconds(1), ToolRetryPolicy.none());
        }

        @Override
        public Class<Input> inputType() {
            return Input.class;
        }

        @Override
        public ToolResult execute(Input input) {
            executions.incrementAndGet();
            return ToolResult.success(input.value());
        }

        int executions() {
            return executions.get();
        }
    }
}
