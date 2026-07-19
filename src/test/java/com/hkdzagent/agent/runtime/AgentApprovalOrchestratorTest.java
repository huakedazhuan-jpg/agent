package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.InMemoryToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentApprovalOrchestratorTest {

    @Test
    void approvalResumesExactlyOnceAfterDurablePause() {
        Fixture fixture = fixture();
        AgentRun run = fixture.createAndPause();
        ToolConfirmation pending = fixture.pending(run);

        ToolConfirmation approved = fixture.orchestrator.approve(pending.id());

        assertThat(approved.status()).isEqualTo(ToolConfirmation.Status.APPROVED);
        assertThat(fixture.runtime.find(run.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.runtime.find(run.runId()).finalAnswer()).isEqualTo("resumed answer");
        verify(fixture.llm, times(1)).resumeWithApprovedTool(
                anyString(), any(AgentExecutionObserver.class));
        assertThatIllegalStateException().isThrownBy(() -> fixture.orchestrator.approve(pending.id()));
        verify(fixture.llm, times(1)).resumeWithApprovedTool(
                anyString(), any(AgentExecutionObserver.class));
    }

    @Test
    void rejectionTerminatesWaitingRunWithoutExecutingTool() {
        Fixture fixture = fixture();
        AgentRun run = fixture.createAndPause();
        ToolConfirmation pending = fixture.pending(run);

        fixture.orchestrator.reject(pending.id(), "command not allowed");

        AgentRun rejected = fixture.runtime.find(run.runId());
        assertThat(rejected.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(rejected.errorMessage()).isEqualTo("command not allowed");
        verify(fixture.llm, times(0)).resumeWithApprovedTool(
                anyString(), any(AgentExecutionObserver.class));
    }

    @Test
    void recoveryResumesApprovalDecidedBeforeProcessRestart() {
        Fixture fixture = fixture();
        AgentRun run = fixture.createAndPause();
        ToolConfirmation pending = fixture.pending(run);
        fixture.confirmations.decidePending(
                pending.id(), ToolConfirmation.Status.APPROVED, "approved",
                Instant.parse("2026-01-01T00:01:00Z"));

        fixture.orchestrator.recoverDecidedApprovals();

        assertThat(fixture.runtime.find(run.runId()).status()).isEqualTo(AgentRunStatus.COMPLETED);
        verify(fixture.llm, times(1)).resumeWithApprovedTool(
                anyString(), any(AgentExecutionObserver.class));
    }

    @Test
    void recoveryExpiresApprovalAndTerminatesWaitingRun() {
        Fixture fixture = fixture();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AgentRun run = fixture.runtime.create(
                ActorIdentity.user("user-a"), "session-expired", "conversation-expired",
                "trace-expired", "run command");
        fixture.recorder.startTrace(
                ActorIdentity.user("user-a"), "trace-expired", "session-expired", "run command");
        AgentRunClaim claim = fixture.runtime.claim(run.runId(), "worker-a");
        String approvalId = "550e8400-e29b-41d4-a716-446655440098";
        fixture.confirmations.save(new ToolConfirmation(
                approvalId, ActorIdentity.user("user-a").key(), "session-expired",
                "trace-expired", run.runId(), "commandExecuteTool", "mvn test",
                ToolConfirmation.Status.PENDING, null,
                now.minusSeconds(60), now.minusSeconds(1), null
        ));
        fixture.runtime.waitForApproval(
                run.runId(), claim.run().leaseOwner(), approvalId, "{\"step\":1}");

        fixture.orchestrator.recoverDecidedApprovals();

        AgentRun expired = fixture.runtime.find(run.runId());
        assertThat(expired.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(expired.errorMessage()).isEqualTo("approval expired");
        verify(fixture.llm, times(0)).resumeWithApprovedTool(
                anyString(), any(AgentExecutionObserver.class));
    }

    private Fixture fixture() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(200);
        InMemoryToolConfirmationRepository confirmations = new InMemoryToolConfirmationRepository();
        ToolConfirmationService confirmationService = new ToolConfirmationService(
                confirmations, sanitizer, Duration.ofMinutes(15), clock);
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(), new AgentRuntimeProperties(), new ObjectMapper(), clock);
        InMemoryAgentTraceRepository traces = new InMemoryAgentTraceRepository();
        AgentTraceRecorder recorder = new AgentTraceRecorder(traces, sanitizer);
        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    observer.modelStarted(1);
                    observer.toolCallRequested(1, "commandExecuteTool", "{\"command\":\"mvn test\"}");
                    assertThat(observer.requiresApproval(
                            1, "commandExecuteTool", "{\"command\":\"mvn test\"}")).isTrue();
                    observer.approvalRequired(
                            1, "commandExecuteTool", "{\"command\":\"mvn test\"}", "{\"step\":1}");
                    return new AgentLoopResult(
                            AgentLoopResult.Status.WAITING_APPROVAL,
                            invocation.getArgument(2), "waiting", List.of());
                });
        when(llm.resumeWithApprovedTool(anyString(), any(AgentExecutionObserver.class)))
                .thenReturn(new AgentLoopResult(
                        AgentLoopResult.Status.COMPLETED, "trace-approval", "resumed answer", List.of()));
        ToolConfirmationProperties approvalProperties = new ToolConfirmationProperties();
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(
                runtime, llm, recorder, sanitizer,
                new AgentApprovalPauseService(confirmationService, runtime, sanitizer),
                approvalProperties);
        AgentApprovalOrchestrator orchestrator = new AgentApprovalOrchestrator(
                confirmationService, confirmations, runtime, runtimeExecutor, recorder, Runnable::run);
        return new Fixture(runtime, confirmations, recorder, llm, runtimeExecutor, orchestrator);
    }

    private record Fixture(
            AgentRuntimeService runtime,
            InMemoryToolConfirmationRepository confirmations,
            AgentTraceRecorder recorder,
            LLMClient llm,
            AgentRuntimeExecutor executor,
            AgentApprovalOrchestrator orchestrator
    ) {
        AgentRun createAndPause() {
            AgentRun run = runtime.create(
                    ActorIdentity.user("user-a"), "session-1", "conversation-1",
                    "trace-approval", "run tests");
            recorder.startTrace(
                    ActorIdentity.user("user-a"), "trace-approval", "session-1", "run tests");
            executor.execute(run.runId(), "worker-a", null);
            assertThat(runtime.find(run.runId()).status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            return run;
        }

        ToolConfirmation pending(AgentRun run) {
            return confirmations.findByStatus(ToolConfirmation.Status.PENDING, 10).stream()
                    .filter(confirmation -> run.runId().equals(confirmation.runId()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
