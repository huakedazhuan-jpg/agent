package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionPipelineTest {

    @Test
    void lowRiskNeverApprovalToolExecutesThroughTheValidatedPipeline() {
        CountingTool tool = new CountingTool(metadata(
                "publicSearch", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER
        ));
        ToolExecutionPipeline pipeline = pipeline(tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false);

        ToolPipelineResult result = pipeline.invoke(context(Set.of("ROLE_USER")),
                "publicSearch", "{\"value\":\"news\"}");

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.COMPLETED);
        assertThat(result.toolResult().message()).isEqualTo("news");
        assertThat(result.argumentsHash()).matches("[0-9a-f]{64}");
        assertThat(tool.executions()).isEqualTo(1);
    }

    @Test
    void assessmentValidatesAndAuthorizesWithoutExecutingTheTool() {
        CountingTool tool = new CountingTool(metadata(
                "publicSearch", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER
        ));
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false);

        ToolPipelineResult result = pipeline.assess(
                context(Set.of("ROLE_USER")), "publicSearch", "{\"value\":\"news\"}");

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.READY);
        assertThat(result.argumentsHash()).matches("[0-9a-f]{64}");
        assertThat(tool.executions()).isZero();
    }

    @Test
    void highRiskAlwaysApprovalToolNeverExecutesBeforeApproval() {
        CountingTool tool = new CountingTool(metadata(
                "writeFile", ToolRiskLevel.HIGH, ToolApprovalPolicy.ALWAYS
        ));
        ToolExecutionPipeline pipeline = pipeline(tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false);

        ToolPipelineResult result = pipeline.invoke(context(Set.of("ROLE_USER")),
                "writeFile", "{\"value\":\"payload\"}");

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.APPROVAL_REQUIRED);
        assertThat(result.argumentsHash()).matches("[0-9a-f]{64}");
        assertThat(result.argumentsPreview()).contains("payload");
        assertThat(result.toolResult()).isNull();
        assertThat(tool.executions()).isZero();
    }

    @Test
    void approvedInvocationExecutesOnlyWhenVersionAndArgumentsHashStillMatch() {
        CountingTool tool = new CountingTool(metadata(
                "writeFile", ToolRiskLevel.HIGH, ToolApprovalPolicy.ALWAYS
        ));
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false);
        ToolInvocationContext context = context(Set.of("ROLE_USER"));
        ToolPipelineResult assessment = pipeline.assess(
                context, "writeFile", "{\"value\":\"payload\"}");

        ToolPipelineResult result = pipeline.invokeApproved(
                context,
                "writeFile",
                "{\"value\":\"payload\"}",
                assessment.toolVersion(),
                assessment.argumentsHash()
        );

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.COMPLETED);
        assertThat(result.toolResult().message()).isEqualTo("payload");
        assertThat(tool.executions()).isOne();
    }

    @Test
    void approvedInvocationRejectsChangedArgumentsBeforeToolExecution() {
        CountingTool tool = new CountingTool(metadata(
                "writeFile", ToolRiskLevel.HIGH, ToolApprovalPolicy.ALWAYS
        ));
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false);
        ToolInvocationContext context = context(Set.of("ROLE_USER"));
        ToolPipelineResult assessment = pipeline.assess(
                context, "writeFile", "{\"value\":\"approved\"}");

        ToolPipelineResult result = pipeline.invokeApproved(
                context,
                "writeFile",
                "{\"value\":\"tampered\"}",
                assessment.toolVersion(),
                assessment.argumentsHash()
        );

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.REJECTED);
        assertThat(result.reason()).contains("arguments hash");
        assertThat(tool.executions()).isZero();
    }

    @Test
    void conditionalPolicyCanRequireApprovalWithoutChangingToolMetadata() {
        CountingTool tool = new CountingTool(metadata(
                "httpGet", ToolRiskLevel.MEDIUM, ToolApprovalPolicy.CONDITIONAL
        ));
        ToolExecutionPipeline pipeline = pipeline(tool, ToolAccessPolicy.allowAuthenticated(), invocation -> true);

        ToolPipelineResult result = pipeline.invoke(context(Set.of("ROLE_USER")),
                "httpGet", "{\"value\":\"https://example.com\"}");

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.APPROVAL_REQUIRED);
        assertThat(tool.executions()).isZero();
    }

    @Test
    void authorizationDenialShortCircuitsBeforeApprovalAndExecution() {
        CountingTool tool = new CountingTool(metadata(
                "adminTool", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER
        ));
        ToolAccessPolicy adminOnly = (context, metadata) -> context.authorities().contains("ROLE_ADMIN")
                ? ToolAccessDecision.allow()
                : ToolAccessDecision.deny("admin role required");
        ToolExecutionPipeline pipeline = pipeline(tool, adminOnly, invocation -> false);

        ToolPipelineResult result = pipeline.invoke(context(Set.of("ROLE_USER")),
                "adminTool", "{\"value\":\"data\"}");

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.REJECTED);
        assertThat(result.reason()).contains("admin role required");
        assertThat(tool.executions()).isZero();
    }

    @Test
    void completedInvocationIsReplayedWithoutExecutingToolAgain() {
        CountingTool tool = new CountingTool(metadata(
                "publicSearch", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER));
        InMemoryToolExecutionJournalRepository journal =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false, journal);
        ToolInvocationContext context = context(Set.of("ROLE_USER"));

        ToolPipelineResult first = pipeline.invoke(
                context, "publicSearch", "{\"value\":\"news\"}");
        ToolPipelineResult replay = pipeline.invoke(
                context, "publicSearch", "{\"value\":\"news\"}");

        assertThat(first.status()).isEqualTo(ToolPipelineResult.Status.COMPLETED);
        assertThat(replay.status()).isEqualTo(ToolPipelineResult.Status.COMPLETED);
        assertThat(replay.toolResult()).isEqualTo(first.toolResult());
        assertThat(tool.executions()).isOne();
    }

    @Test
    void reusedToolCallIdWithDifferentArgumentsIsRejected() {
        CountingTool tool = new CountingTool(metadata(
                "publicSearch", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER));
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false,
                new InMemoryToolExecutionJournalRepository());
        ToolInvocationContext context = context(Set.of("ROLE_USER"));
        pipeline.invoke(context, "publicSearch", "{\"value\":\"first\"}");

        ToolPipelineResult conflict = pipeline.invoke(
                context, "publicSearch", "{\"value\":\"changed\"}");

        assertThat(conflict.status()).isEqualTo(ToolPipelineResult.Status.REJECTED);
        assertThat(conflict.reason()).contains("already bound");
        assertThat(tool.executions()).isOne();
    }

    @Test
    void previouslyStartedInvocationIsNotExecutedAgain() {
        CountingTool tool = new CountingTool(metadata(
                "publicSearch", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER));
        InMemoryToolExecutionJournalRepository journal =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false, journal);
        ToolInvocationContext context = context(Set.of("ROLE_USER"));
        ToolPipelineResult assessment = pipeline.assess(
                context, "publicSearch", "{\"value\":\"news\"}");
        journal.reserve(ToolExecutionJournalEntry.started(
                context, assessment.toolName(), assessment.toolVersion(),
                assessment.argumentsHash(), UUID.randomUUID().toString(),
                Instant.parse("2026-01-01T00:00:00Z")));

        ToolPipelineResult result = pipeline.invoke(
                context, "publicSearch", "{\"value\":\"news\"}");

        assertThat(result.status())
                .isEqualTo(ToolPipelineResult.Status.EXECUTION_UNCERTAIN);
        assertThat(result.reason()).contains("no durable result");
        assertThat(tool.executions()).isZero();
    }

    @Test
    void processCrashLeavesStartedJournalThatBlocksRetry() {
        CrashingTool tool = new CrashingTool();
        InMemoryToolExecutionJournalRepository journal =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionPipeline pipeline = pipeline(
                tool, ToolAccessPolicy.allowAuthenticated(), invocation -> false, journal);
        ToolInvocationContext context = context(Set.of("ROLE_USER"));

        assertThatThrownBy(() -> pipeline.invoke(
                context, "crashingTool", "{\"value\":\"side effect\"}"))
                .isInstanceOf(AssertionError.class);
        ToolPipelineResult retry = pipeline.invoke(
                context, "crashingTool", "{\"value\":\"side effect\"}");

        assertThat(retry.status())
                .isEqualTo(ToolPipelineResult.Status.EXECUTION_UNCERTAIN);
        assertThat(tool.executions()).isOne();
    }

    private ToolExecutionPipeline pipeline(
            CountingTool tool,
            ToolAccessPolicy accessPolicy,
            ToolApprovalCondition approvalCondition
    ) {
        return pipeline(
                tool, accessPolicy, approvalCondition,
                new InMemoryToolExecutionJournalRepository());
    }

    private ToolExecutionPipeline pipeline(
            AgentTool<?, ?> tool,
            ToolAccessPolicy accessPolicy,
            ToolApprovalCondition approvalCondition,
            ToolExecutionJournalRepository journal
    ) {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        ToolInvocationValidator validator = new ToolInvocationValidator(registry, new ObjectMapper(), 160);
        return new ToolExecutionPipeline(
                validator, new ToolPolicyEngine(accessPolicy, approvalCondition), journal,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private ToolInvocationContext context(Set<String> authorities) {
        return new ToolInvocationContext(
                "user:42", "550e8400-e29b-41d4-a716-446655440000",
                "trace-1", "call-1", authorities
        );
    }

    private ToolMetadata metadata(
            String name,
            ToolRiskLevel risk,
            ToolApprovalPolicy approvalPolicy
    ) {
        return new ToolMetadata(
                name, "1.0.0",
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"],\"additionalProperties\":false}",
                risk, approvalPolicy, Duration.ofSeconds(1), ToolRetryPolicy.none()
        );
    }

    private record Input(String value) {
    }

    private static final class CountingTool implements AgentTool<Input, ToolResult> {
        private final ToolMetadata metadata;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(ToolMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public ToolMetadata metadata() {
            return metadata;
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

        private int executions() {
            return executions.get();
        }
    }

    private final class CrashingTool implements AgentTool<Input, ToolResult> {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolMetadata metadata() {
            return ToolExecutionPipelineTest.this.metadata(
                    "crashingTool", ToolRiskLevel.HIGH, ToolApprovalPolicy.NEVER);
        }

        @Override
        public Class<Input> inputType() {
            return Input.class;
        }

        @Override
        public ToolResult execute(Input input) {
            executions.incrementAndGet();
            throw new AssertionError("simulated process crash after side effect");
        }

        private int executions() {
            return executions.get();
        }
    }
}
