package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeExecutorTest {

    @Test
    void persistsIncrementalExecutionAndCompletesRun() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRuntimeService runtime = new AgentRuntimeService(
                repository, new AgentRuntimeProperties(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
        InMemoryAgentTraceRepository traces = new InMemoryAgentTraceRepository();
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(120);
        AgentTraceRecorder recorder = new AgentTraceRecorder(traces, sanitizer);
        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    observer.modelStarted(1);
                    observer.tokenDelta(1, "hello ");
                    observer.tokenDelta(1, "world");
                    observer.modelCompleted(1);
                    return new AgentLoopResult(
                            AgentLoopResult.Status.COMPLETED,
                            invocation.getArgument(2), "hello world", List.of());
                });
        AgentRuntimeExecutor executor = new AgentRuntimeExecutor(
                runtime, llm, recorder, new AgentTraceSanitizer(120),
                new AgentApprovalPauseService(
                        new ToolConfirmationService(new AgentTraceSanitizer(120)),
                        runtime, new AgentTraceSanitizer(120)),
                new ToolConfirmationProperties());
        AgentRun run = runtime.create(
                ActorIdentity.user("user-a"), "session-1", "conversation-1", "trace-1", "question");
        recorder.startTrace(ActorIdentity.user("user-a"), "trace-1", "session-1", "question");
        List<AgentRunEvent> liveEvents = new ArrayList<>();

        executor.execute(run.runId(), "worker-a", liveEvents::add);

        AgentRun completed = runtime.find(run.runId());
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.finalAnswer()).isEqualTo("hello world");
        assertThat(runtime.replayEvents(run.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsExactly(
                        AgentRunEventType.RUN_CREATED,
                        AgentRunEventType.RUN_STARTED,
                        AgentRunEventType.MODEL_STARTED,
                        AgentRunEventType.TOKEN_DELTA,
                        AgentRunEventType.TOKEN_DELTA,
                        AgentRunEventType.MODEL_COMPLETED,
                        AgentRunEventType.RUN_COMPLETED
                );
        assertThat(liveEvents).hasSize(6);
        assertThat(traces.findByTraceId("trace-1").status().name()).isEqualTo("COMPLETED");
    }

    @Test
    void staleWorkerStopsWithoutFailingRunClaimedByNewWorker() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRuntimeService runtime = new AgentRuntimeService(
                repository, properties, new ObjectMapper(), clock);
        AgentRun created = runtime.create(
                ActorIdentity.user("user-b"), "session-2", "conversation-2", "trace-2", "question");
        InMemoryAgentTraceRepository traces = new InMemoryAgentTraceRepository();
        AgentTraceRecorder recorder = new AgentTraceRecorder(traces, new AgentTraceSanitizer(120));
        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    observer.modelStarted(1);
                    clock.advance(Duration.ofSeconds(31));
                    assertThat(runtime.claim(created.runId(), "worker-b")).isNotNull();
                    observer.tokenDelta(1, "stale output");
                    throw new AssertionError("stale Worker callback should have been rejected");
                });
        AgentRuntimeExecutor executor = new AgentRuntimeExecutor(
                runtime, llm, recorder, new AgentTraceSanitizer(120),
                new AgentApprovalPauseService(
                        new ToolConfirmationService(new AgentTraceSanitizer(120)),
                        runtime, new AgentTraceSanitizer(120)),
                new ToolConfirmationProperties());
        recorder.startTrace(ActorIdentity.user("user-b"), "trace-2", "session-2", "question");

        assertThatThrownBy(() -> executor.execute(created.runId(), "worker-a", null))
                .isInstanceOf(AgentRunLeaseLostException.class);

        AgentRun claimedByReplacement = runtime.find(created.runId());
        assertThat(claimedByReplacement.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(claimedByReplacement.leaseOwner()).isEqualTo("worker-b");
        assertThat(claimedByReplacement.leaseEpoch()).isEqualTo(2);
        assertThat(runtime.replayEvents(created.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsExactly(
                        AgentRunEventType.RUN_CREATED,
                        AgentRunEventType.RUN_STARTED,
                        AgentRunEventType.MODEL_STARTED);
    }

    @Test
    void cancellationDuringModelCallStopsWithoutOverwritingCancelledState() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRuntimeService runtime = new AgentRuntimeService(
                repository, new AgentRuntimeProperties(), new ObjectMapper(), Clock.systemUTC());
        InMemoryAgentTraceRepository traces = new InMemoryAgentTraceRepository();
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(120);
        AgentTraceRecorder recorder = new AgentTraceRecorder(traces, sanitizer);
        AgentCancellationService cancellationService = new AgentCancellationService(
                runtime, recorder, sanitizer);
        AgentRun run = runtime.create(
                ActorIdentity.user("user-c"), "session-3", "conversation-3", "trace-3", "question");
        recorder.startTrace(ActorIdentity.user("user-c"), "trace-3", "session-3", "question");
        LLMClient llm = mock(LLMClient.class);
        when(llm.runWithTools(anyString(), anyString(), anyString(), any(AgentExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    AgentExecutionObserver observer = invocation.getArgument(3);
                    observer.modelStarted(1);
                    assertThat(cancellationService.cancel(
                                    ActorIdentity.user("user-c"), run.runId(), "model no longer needed")
                            .newlyCancelled()).isTrue();
                    return new AgentLoopResult(
                            AgentLoopResult.Status.COMPLETED,
                            invocation.getArgument(2), "late answer", List.of());
                });
        AgentRuntimeExecutor executor = new AgentRuntimeExecutor(
                runtime, llm, recorder, new AgentTraceSanitizer(120),
                new AgentApprovalPauseService(
                        new ToolConfirmationService(new AgentTraceSanitizer(120)),
                        runtime, new AgentTraceSanitizer(120)),
                new ToolConfirmationProperties());

        executor.execute(run.runId(), "worker-c", null);

        AgentRun cancelled = runtime.find(run.runId());
        assertThat(cancelled.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(cancelled.finalAnswer()).isNull();
        assertThat(cancelled.errorMessage()).isNull();
        assertThat(runtime.replayEvents(run.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsExactly(
                        AgentRunEventType.RUN_CREATED,
                        AgentRunEventType.RUN_STARTED,
                        AgentRunEventType.MODEL_STARTED,
                        AgentRunEventType.RUN_CANCELLED);
        assertThat(traces.findByTraceId("trace-3").status().name()).isEqualTo("CANCELLED");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
