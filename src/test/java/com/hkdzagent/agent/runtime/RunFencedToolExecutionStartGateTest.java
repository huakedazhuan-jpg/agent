package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.InMemoryToolExecutionJournalRepository;
import com.hkdzagent.agent.tool.ToolExecutionFence;
import com.hkdzagent.agent.tool.ToolExecutionJournalEntry;
import com.hkdzagent.agent.tool.ToolExecutionStartGate;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunFencedToolExecutionStartGateTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ActorIdentity OWNER = ActorIdentity.user("gate-user");

    @Test
    void cancellationCommittedBeforeGatePreventsJournalReservation() {
        Fixture fixture = fixture();
        assertThat(fixture.runtime.cancelOwned(fixture.run.runId(), OWNER).newlyCancelled())
                .isTrue();

        ToolExecutionStartGate.Decision decision = fixture.gate.reserve(
                fixture.candidate, fixture.fence);

        assertThat(decision.allowed()).isFalse();
        assertThat(fixture.journal.find(
                fixture.run.runId(), fixture.candidate.toolCallId())).isNull();
    }

    @Test
    void gateCommittedBeforeCancellationLeavesStartedEvidence() {
        Fixture fixture = fixture();

        ToolExecutionStartGate.Decision decision = fixture.gate.reserve(
                fixture.candidate, fixture.fence);
        assertThat(fixture.runtime.cancelOwned(fixture.run.runId(), OWNER).newlyCancelled())
                .isTrue();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reservation().acquired()).isTrue();
        assertThat(fixture.journal.find(
                fixture.run.runId(), fixture.candidate.toolCallId()).status())
                .isEqualTo(ToolExecutionJournalEntry.Status.STARTED);
    }

    @Test
    void staleLeaseEpochCannotReserveJournalEntry() {
        Fixture fixture = fixture();

        ToolExecutionStartGate.Decision decision = fixture.gate.reserve(
                fixture.candidate,
                new ToolExecutionFence(fixture.fence.workerId(), fixture.fence.leaseEpoch() + 1));

        assertThat(decision.allowed()).isFalse();
        assertThat(fixture.journal.find(
                fixture.run.runId(), fixture.candidate.toolCallId())).isNull();
    }

    private Fixture fixture() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        AgentRuntimeService runtime = new AgentRuntimeService(
                runRepository, new AgentRuntimeProperties(), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        AgentRun created = runtime.create(
                OWNER, "gate-session", "gate-conversation", "gate-trace", "run tool");
        AgentRun run = runtime.claim(created.runId(), "gate-worker").run();
        InMemoryToolExecutionJournalRepository journal =
                new InMemoryToolExecutionJournalRepository();
        RunFencedToolExecutionStartGate gate =
                new RunFencedToolExecutionStartGate(runRepository, journal);
        ToolInvocationContext context = new ToolInvocationContext(
                run.ownerKey(), run.runId(), run.traceId(), "gate-call", Set.of());
        ToolExecutionJournalEntry candidate = ToolExecutionJournalEntry.started(
                context, "writeFile", "1.0.0", "a".repeat(64),
                UUID.randomUUID().toString(), NOW);
        return new Fixture(
                runtime, run, journal, gate, candidate,
                new ToolExecutionFence(run.leaseOwner(), run.leaseEpoch()));
    }

    private record Fixture(
            AgentRuntimeService runtime,
            AgentRun run,
            InMemoryToolExecutionJournalRepository journal,
            RunFencedToolExecutionStartGate gate,
            ToolExecutionJournalEntry candidate,
            ToolExecutionFence fence
    ) {
    }
}
