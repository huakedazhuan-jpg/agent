package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.InMemoryToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.AgentTool;
import com.hkdzagent.agent.tool.AgentToolRegistry;
import com.hkdzagent.agent.tool.ToolAccessPolicy;
import com.hkdzagent.agent.tool.ToolApprovalPolicy;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.tool.InMemoryToolExecutionJournalRepository;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolInvocationValidator;
import com.hkdzagent.agent.tool.ToolMetadata;
import com.hkdzagent.agent.tool.ToolPipelineResult;
import com.hkdzagent.agent.tool.ToolPolicyEngine;
import com.hkdzagent.agent.tool.ToolResult;
import com.hkdzagent.agent.tool.ToolRetryPolicy;
import com.hkdzagent.agent.tool.ToolRiskLevel;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovedToolExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String APPROVAL_ID = "550e8400-e29b-41d4-a716-446655440099";

    @Test
    void approvedBindingExecutesThroughPipelineAndReturnsObservation() {
        Fixture fixture = fixture("{\"value\":\"approved\"}");

        ApprovedToolExecution execution = fixture.service.execute(
                fixture.runningRun, APPROVAL_ID);
        ApprovedToolExecution replay = fixture.service.execute(
                fixture.runningRun, APPROVAL_ID);

        assertThat(execution.step()).isOne();
        assertThat(execution.toolCallId()).isEqualTo("call-1");
        assertThat(execution.observation().toolName()).isEqualTo("writeFile");
        assertThat(execution.observation().content()).isEqualTo("approved");
        assertThat(execution.observation().success()).isTrue();
        assertThat(replay.observation()).isEqualTo(execution.observation());
        assertThat(fixture.tool.executions()).isOne();
    }

    @Test
    void checkpointArgumentsChangedAfterApprovalAreRejectedBeforeExecution() {
        Fixture fixture = fixture("{\"value\":\"tampered\"}");

        assertThatThrownBy(() -> fixture.service.execute(fixture.runningRun, APPROVAL_ID))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("arguments hash");
        assertThat(fixture.tool.executions()).isZero();
    }

    private Fixture fixture(String checkpointArguments) {
        ObjectMapper objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CountingTool tool = new CountingTool();
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryToolExecutionJournalRepository journalRepository =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                new ToolInvocationValidator(
                        new AgentToolRegistry(List.of(tool)), objectMapper, 160),
                new ToolPolicyEngine(
                        ToolAccessPolicy.allowAuthenticated(), invocation -> false),
                journalRepository,
                new RunFencedToolExecutionStartGate(runRepository, journalRepository),
                clock);
        InMemoryToolConfirmationRepository repository =
                new InMemoryToolConfirmationRepository();
        ToolConfirmationService confirmations = new ToolConfirmationService(
                repository, new AgentTraceSanitizer(160), Duration.ofMinutes(15), clock);
        AgentRuntimeService runtime = new AgentRuntimeService(
                runRepository,
                new AgentRuntimeProperties(), objectMapper, clock);
        AgentRun created = runtime.create(
                ActorIdentity.user("user-a"), "session-1", "conversation-1",
                "trace-1", "write file");
        AgentRunClaim claim = runtime.claim(created.runId(), "worker-1");
        ToolInvocationContext context = new ToolInvocationContext(
                created.ownerKey(), created.runId(), created.traceId(), "call-1", Set.of());
        String approvedArguments = "{\"value\":\"approved\"}";
        ToolPipelineResult assessment = pipeline.assess(
                context, "writeFile", approvedArguments);
        repository.save(new ToolConfirmation(
                APPROVAL_ID,
                created.ownerKey(), created.sessionId(), created.traceId(), created.runId(),
                assessment.toolName(), assessment.toolVersion(), context.toolCallId(),
                assessment.argumentsHash(), assessment.argumentsPreview(),
                ToolConfirmation.Status.APPROVED, "approved",
                NOW, NOW.plusSeconds(900), NOW.plusSeconds(1)
        ));
        String checkpoint = """
                {"schemaVersion":1,"traceId":"trace-1","step":1,
                 "toolCall":{"id":"call-1","function":{"name":"writeFile","arguments":%s}}}
                """.formatted(objectMapper.valueToTree(checkpointArguments).toString());
        runtime.waitForApproval(
                created.runId(), claim.run().leaseOwner(), claim.run().leaseEpoch(),
                APPROVAL_ID, checkpoint);
        AgentRun running = runtime.resumeApproval(
                created.runId(), APPROVAL_ID, "approval-worker");
        return new Fixture(
                new ApprovedToolExecutionService(confirmations, pipeline, objectMapper),
                running, tool);
    }

    private record Fixture(
            ApprovedToolExecutionService service,
            AgentRun runningRun,
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
