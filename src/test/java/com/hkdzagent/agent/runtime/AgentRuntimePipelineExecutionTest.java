package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.loop.AgentObservation;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.AgentTool;
import com.hkdzagent.agent.tool.AgentToolRegistry;
import com.hkdzagent.agent.tool.ToolAccessPolicy;
import com.hkdzagent.agent.tool.ToolApprovalPolicy;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.tool.InMemoryToolExecutionJournalRepository;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimePipelineExecutionTest {

    @Test
    void readyToolExecutesThroughRuntimePipelineWithTheAssessedCallIdentity() {
        Fixture fixture = fixture(Scenario.NORMAL);

        fixture.executor.execute(fixture.run.runId(), "worker-1", null);

        AgentRun completed = fixture.runtime.find(fixture.run.runId());
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.finalAnswer()).isEqualTo("tool result accepted");
        assertThat(fixture.tool.executions()).isOne();
    }

    @Test
    void changedArgumentsBetweenAssessmentAndExecutionAreRejectedWithoutExecuting() {
        Fixture fixture = fixture(Scenario.CHANGED_ARGUMENTS);

        fixture.executor.execute(fixture.run.runId(), "worker-1", null);

        AgentRun failed = fixture.runtime.find(fixture.run.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).contains("does not match a ready tool assessment");
        assertThat(fixture.tool.executions()).isZero();
    }

    @Test
    void policyChangingToApprovalRequiredAfterAssessmentPreventsExecution() {
        Fixture fixture = fixture(Scenario.POLICY_DRIFT);

        fixture.executor.execute(fixture.run.runId(), "worker-1", null);

        AgentRun failed = fixture.runtime.find(fixture.run.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).contains("policy changed");
        assertThat(fixture.tool.executions()).isZero();
    }

    private Fixture fixture(Scenario scenario) {
        ObjectMapper objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        AgentRuntimeService runtime = new AgentRuntimeService(
                runRepository,
                new AgentRuntimeProperties(), objectMapper, clock);
        AgentTraceRecorder recorder = new AgentTraceRecorder(
                new InMemoryAgentTraceRepository(), sanitizer);
        CountingTool tool = new CountingTool();
        AtomicBoolean approvalRequired = new AtomicBoolean(false);
        InMemoryToolExecutionJournalRepository journalRepository =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolInvocationValidator(
                        new AgentToolRegistry(List.of(tool)), objectMapper, 160),
                new ToolPolicyEngine(
                        ToolAccessPolicy.allowAuthenticated(),
                        invocation -> approvalRequired.get()),
                journalRepository,
                new RunFencedToolExecutionStartGate(runRepository, journalRepository),
                clock);
        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(
                anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    String assessedArguments = "{\"value\":\"safe\"}";
                    observer.modelStarted(1);
                    observer.toolCallRequested(
                            1, "call-ready-1", "publicTool", assessedArguments);
                    assertThat(observer.requiresApproval(
                            1, "call-ready-1", "publicTool", assessedArguments)).isFalse();
                    if (scenario == Scenario.POLICY_DRIFT) {
                        approvalRequired.set(true);
                    }
                    String executionArguments = scenario == Scenario.CHANGED_ARGUMENTS
                            ? "{\"value\":\"changed\"}"
                            : assessedArguments;
                    observer.toolStarted(1, "publicTool");
                    AgentObservation observation = observer.executeTool(
                            1, "call-ready-1", "publicTool", executionArguments);
                    observer.toolCompleted(
                            1, observation.toolName(),
                            observation.success(), observation.content());
                    return new AgentLoopResult(
                            AgentLoopResult.Status.COMPLETED,
                            "trace-pipeline", "tool result accepted", List.of());
                });

        ToolConfirmationService confirmationService =
                new ToolConfirmationService(sanitizer);
        AgentRuntimeExecutor executor = new AgentRuntimeExecutor(
                runtime, llm, recorder, sanitizer,
                new AgentApprovalPauseService(
                        confirmationService, runtime, sanitizer, objectMapper),
                new ToolConfirmationProperties(), pipeline);
        AgentRun run = runtime.create(
                ActorIdentity.user("pipeline-user"),
                "session-pipeline", "conversation-pipeline",
                "trace-pipeline", "use public tool");
        recorder.startTrace(
                ActorIdentity.user("pipeline-user"), run.traceId(),
                run.sessionId(), run.userMessage());
        return new Fixture(runtime, executor, run, tool);
    }

    private enum Scenario {
        NORMAL,
        CHANGED_ARGUMENTS,
        POLICY_DRIFT
    }

    private record Fixture(
            AgentRuntimeService runtime,
            AgentRuntimeExecutor executor,
            AgentRun run,
            CountingTool tool
    ) {
    }

    private record Input(String value) {
    }

    private static final class CountingTool implements AgentTool<Input, ToolResult> {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "publicTool", "1.0.0",
                    "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"],\"additionalProperties\":false}",
                    ToolRiskLevel.LOW, ToolApprovalPolicy.CONDITIONAL,
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
