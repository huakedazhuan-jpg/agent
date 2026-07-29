package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.InMemoryToolExecutionJournalRepository;
import com.hkdzagent.agent.tool.ToolExecutionJournalEntry;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentRunRecoveryServiceTest {

    private final Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant recoveryTime = startedAt.plusSeconds(31);

    @Test
    void dispatchesModelOnlyRunWithoutClaimingItTwice() {
        Fixture fixture = fixture(3);
        fixture.repository.appendWorkerEvent(
                fixture.run.runId(), "original-worker", fixture.run.leaseEpoch(),
                AgentRunEventType.MODEL_STARTED, "{}", startedAt.plusSeconds(1));

        assertThat(fixture.service.recoverExpiredRuns()).isOne();

        verify(fixture.executor).executeClaimed(
                any(AgentRun.class), startsWith("recovery-"), isNull());
        AgentRun recovered = fixture.runtime.find(fixture.run.runId());
        assertThat(recovered.leaseEpoch()).isEqualTo(fixture.run.leaseEpoch() + 1);
        assertThat(fixture.runtime.replayEvents(fixture.run.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsSubsequence(
                        AgentRunEventType.MODEL_STARTED,
                        AgentRunEventType.RUN_RECOVERY_STARTED);
        assertThat(fixture.runtime.recoveryEvidence(fixture.run.runId()).recoveryAttempts())
                .isOne();
    }

    @Test
    void blocksRunWhenToolExecutionMayHaveProducedSideEffect() {
        Fixture fixture = fixture(3);
        fixture.repository.appendWorkerEvent(
                fixture.run.runId(), "original-worker", fixture.run.leaseEpoch(),
                AgentRunEventType.TOOL_STARTED, "{}", startedAt.plusSeconds(1));

        assertThat(fixture.service.recoverExpiredRuns()).isOne();

        verify(fixture.executor, never()).executeClaimed(
                any(AgentRun.class), any(String.class), any());
        AgentRun failed = fixture.runtime.find(fixture.run.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).contains("without journal evidence");
        assertThat(fixture.runtime.replayEvents(fixture.run.runId(), 0))
                .extracting(AgentRunEvent::type)
                .containsSubsequence(
                        AgentRunEventType.TOOL_STARTED,
                        AgentRunEventType.RUN_RECOVERY_BLOCKED,
                        AgentRunEventType.RUN_FAILED);
    }

    @Test
    void blocksUncertainJournalEntryEvenWhenToolStartedEventWasNotWritten() {
        Fixture fixture = fixture(3);
        fixture.journal.reserve(journalEntry(fixture.run, "approval-call"));

        assertThat(fixture.service.recoverExpiredRuns()).isOne();

        verify(fixture.executor, never()).executeClaimed(
                any(AgentRun.class), any(String.class), any());
        AgentRun failed = fixture.runtime.find(fixture.run.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).contains("no durable result");
        assertThat(fixture.runtime.replayEvents(fixture.run.runId(), 0))
                .filteredOn(event -> event.type() == AgentRunEventType.RUN_RECOVERY_BLOCKED)
                .singleElement()
                .satisfies(event -> assertThat(event.payloadJson())
                        .contains("BLOCKED_TOOL_EXECUTION_UNCERTAIN", "\"journalStarted\":1"));
    }

    @Test
    void blocksCompletedToolUntilDurableModelCheckpointExists() {
        Fixture fixture = fixture(3);
        ToolExecutionJournalEntry started = journalEntry(fixture.run, "completed-call");
        fixture.journal.reserve(started);
        fixture.journal.complete(
                started.runId(), started.toolCallId(), started.executionToken(),
                ToolResult.success("done"), startedAt.plusSeconds(2));

        assertThat(fixture.service.recoverExpiredRuns()).isOne();

        verify(fixture.executor, never()).executeClaimed(
                any(AgentRun.class), any(String.class), any());
        AgentRun failed = fixture.runtime.find(fixture.run.runId());
        assertThat(failed.errorMessage()).contains("durable model checkpoint");
    }

    @Test
    void blocksRunAfterConfiguredRecoveryAttemptLimit() {
        Fixture fixture = fixture(2);
        fixture.repository.appendEvent(
                fixture.run.runId(), AgentRunEventType.RUN_RECOVERY_STARTED,
                "{\"attempt\":1}", startedAt.plusSeconds(1));
        fixture.repository.appendEvent(
                fixture.run.runId(), AgentRunEventType.RUN_RECOVERY_STARTED,
                "{\"attempt\":2}", startedAt.plusSeconds(2));

        assertThat(fixture.service.recoverExpiredRuns()).isOne();

        verify(fixture.executor, never()).executeClaimed(
                any(AgentRun.class), any(String.class), any());
        AgentRun failed = fixture.runtime.find(fixture.run.runId());
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.errorMessage()).contains("attempts exhausted");
    }

    private Fixture fixture(int maxRecoveryAttempts) {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        properties.setMaxRecoveryAttempts(maxRecoveryAttempts);
        properties.setRecoveryBatchSize(10);
        AgentRuntimeService runtime = new AgentRuntimeService(
                repository, properties, new ObjectMapper(),
                Clock.fixed(recoveryTime, ZoneOffset.UTC));
        AgentRun stored = repository.create(AgentRun.created(
                UUID.randomUUID().toString(),
                ActorIdentity.user("recovery-user"),
                "recovery-session", "recovery-conversation",
                UUID.randomUUID().toString(), "recover me", 5, startedAt), "{}");
        AgentRun claimed = repository.claim(
                stored.runId(), "original-worker", startedAt, Duration.ofSeconds(30)).run();
        AgentRuntimeExecutor runtimeExecutor = mock(AgentRuntimeExecutor.class);
        InMemoryToolExecutionJournalRepository journal =
                new InMemoryToolExecutionJournalRepository();
        AgentFailureService failureService = new AgentFailureService(
                runtime, mock(FeishuResultOutboxService.class));
        AgentRunRecoveryService service = new AgentRunRecoveryService(
                runtime, runtimeExecutor, failureService,
                new AgentRunRecoveryClassifier(), journal, properties, Runnable::run);
        return new Fixture(repository, runtime, runtimeExecutor, journal, service, claimed);
    }

    private ToolExecutionJournalEntry journalEntry(AgentRun run, String toolCallId) {
        return ToolExecutionJournalEntry.started(
                new ToolInvocationContext(
                        run.ownerKey(), run.runId(), run.traceId(), toolCallId, Set.of()),
                "writeFile", "1.0.0", "a".repeat(64),
                UUID.randomUUID().toString(), startedAt.plusSeconds(1));
    }

    private record Fixture(
            InMemoryAgentRunRepository repository,
            AgentRuntimeService runtime,
            AgentRuntimeExecutor executor,
            InMemoryToolExecutionJournalRepository journal,
            AgentRunRecoveryService service,
            AgentRun run
    ) {
    }
}
