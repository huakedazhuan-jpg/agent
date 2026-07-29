package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryToolExecutionJournalRepositoryTest {

    @Test
    void concurrentReservationsProduceOneExecutionOwner() throws Exception {
        InMemoryToolExecutionJournalRepository repository =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionJournalEntry first = candidate();
        ToolExecutionJournalEntry second = new ToolExecutionJournalEntry(
                first.runId(), first.toolCallId(), first.toolName(), first.toolVersion(),
                first.argumentsHash(), ToolExecutionJournalEntry.Status.STARTED,
                null, null, UUID.randomUUID().toString(), first.startedAt(), null);
        CyclicBarrier barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                barrier.await();
                return repository.reserve(first);
            });
            var two = executor.submit(() -> {
                barrier.await();
                return repository.reserve(second);
            });

            var reservations = java.util.List.of(one.get(), two.get());
            assertThat(reservations).filteredOn(
                    ToolExecutionJournalRepository.Reservation::acquired).hasSize(1);
            assertThat(reservations).extracting(reservation -> reservation.entry().executionToken())
                    .containsOnly(repository.find(first.runId(), first.toolCallId()).executionToken());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completionIsFencedByExecutionToken() {
        InMemoryToolExecutionJournalRepository repository =
                new InMemoryToolExecutionJournalRepository();
        ToolExecutionJournalEntry candidate = candidate();
        repository.reserve(candidate);

        assertThat(repository.complete(
                candidate.runId(), candidate.toolCallId(), UUID.randomUUID().toString(),
                ToolResult.success("stale"), candidate.startedAt().plusSeconds(1))).isNull();
        ToolExecutionJournalEntry completed = repository.complete(
                candidate.runId(), candidate.toolCallId(), candidate.executionToken(),
                ToolResult.success("done"), candidate.startedAt().plusSeconds(2));

        assertThat(completed.status()).isEqualTo(ToolExecutionJournalEntry.Status.COMPLETED);
        assertThat(completed.resultStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(completed.resultMessage()).isEqualTo("done");
        assertThat(repository.complete(
                candidate.runId(), candidate.toolCallId(), candidate.executionToken(),
                ToolResult.success("again"), candidate.startedAt().plusSeconds(3))).isNull();
    }

    private ToolExecutionJournalEntry candidate() {
        ToolInvocationContext context = new ToolInvocationContext(
                "user:journal", UUID.randomUUID().toString(), "trace-journal", "call-1", Set.of());
        return ToolExecutionJournalEntry.started(
                context, "writeFile", "1.0.0", "a".repeat(64),
                UUID.randomUUID().toString(), Instant.parse("2026-01-01T00:00:00Z"));
    }
}
