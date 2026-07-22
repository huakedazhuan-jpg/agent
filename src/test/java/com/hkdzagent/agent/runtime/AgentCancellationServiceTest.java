package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import com.hkdzagent.agent.trace.TraceStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCancellationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void ownerCancelsRunningRunOnceAndStaleWorkerCannotComplete() {
        Fixture fixture = fixture();
        AgentRunClaim claim = fixture.runtime.claim(fixture.run.runId(), "worker-a");

        AgentRunCancellation cancelled = fixture.cancellations.cancel(
                fixture.owner, fixture.run.runId(), "user requested stop api_key=secret");
        AgentRunCancellation duplicate = fixture.cancellations.cancel(
                fixture.owner, fixture.run.runId(), "duplicate");

        assertThat(cancelled.outcome()).isEqualTo(AgentRunCancellation.Outcome.CANCELLED);
        assertThat(cancelled.run().status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(cancelled.run().leaseOwner()).isNull();
        assertThat(duplicate.outcome())
                .isEqualTo(AgentRunCancellation.Outcome.ALREADY_CANCELLED);
        assertThat(fixture.runtime.replayEvents(fixture.run.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsExactly(AgentRunEventType.RUN_CREATED, AgentRunEventType.RUN_CANCELLED);
        assertThat(fixture.runtime.replayEvents(fixture.run.runId(), 0).get(1).payloadJson())
                .doesNotContain("secret");
        assertThat(fixture.traces.findByTraceId(fixture.run.traceId()).status())
                .isEqualTo(TraceStatus.CANCELLED);
        assertThatThrownBy(() -> fixture.runtime.complete(
                fixture.run.runId(), "worker-a", claim.run().leaseEpoch(), "late answer"))
                .isInstanceOf(AgentRunLeaseLostException.class);
    }

    @Test
    void cancellationDoesNotRevealAnotherOwnersRun() {
        Fixture fixture = fixture();

        AgentRunCancellation result = fixture.cancellations.cancel(
                ActorIdentity.user("other-user"), fixture.run.runId(), null);

        assertThat(result.outcome()).isEqualTo(AgentRunCancellation.Outcome.NOT_FOUND);
        assertThat(result.run()).isNull();
        assertThat(fixture.runtime.find(fixture.run.runId()).status())
                .isEqualTo(AgentRunStatus.CREATED);
    }

    @Test
    void completedRunProducesTerminalConflict() {
        Fixture fixture = fixture();
        AgentRunClaim claim = fixture.runtime.claim(fixture.run.runId(), "worker-a");
        fixture.runtime.complete(
                fixture.run.runId(), "worker-a", claim.run().leaseEpoch(), "done");

        AgentRunCancellation result = fixture.cancellations.cancel(
                fixture.owner, fixture.run.runId(), null);

        assertThat(result.outcome())
                .isEqualTo(AgentRunCancellation.Outcome.TERMINAL_CONFLICT);
        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
    }

    private Fixture fixture() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setLeaseDuration(Duration.ofMinutes(1));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentRuntimeService runtime = new AgentRuntimeService(
                new InMemoryAgentRunRepository(), properties, new ObjectMapper(), clock);
        InMemoryAgentTraceRepository traces = new InMemoryAgentTraceRepository();
        AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
        AgentTraceRecorder recorder = new AgentTraceRecorder(traces, sanitizer);
        ActorIdentity owner = ActorIdentity.user("cancel-user");
        recorder.startTrace(owner, "trace-cancel", "session-cancel", "cancel me");
        AgentRun run = runtime.create(
                owner, "session-cancel", "conversation-cancel", "trace-cancel", "cancel me");
        return new Fixture(
                owner, run, runtime, traces,
                new AgentCancellationService(runtime, recorder, sanitizer));
    }

    private record Fixture(
            ActorIdentity owner,
            AgentRun run,
            AgentRuntimeService runtime,
            InMemoryAgentTraceRepository traces,
            AgentCancellationService cancellations
    ) {
    }
}
