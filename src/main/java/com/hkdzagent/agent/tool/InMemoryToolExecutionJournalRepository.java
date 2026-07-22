package com.hkdzagent.agent.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryToolExecutionJournalRepository implements ToolExecutionJournalRepository {

    private final ConcurrentMap<Key, ToolExecutionJournalEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Reservation reserve(ToolExecutionJournalEntry candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        ToolExecutionJournalEntry existing = entries.putIfAbsent(
                new Key(candidate.runId(), candidate.toolCallId()), candidate);
        return existing == null
                ? new Reservation(true, candidate)
                : new Reservation(false, existing);
    }

    @Override
    public ToolExecutionJournalEntry find(String runId, String toolCallId) {
        return entries.get(new Key(runId, toolCallId));
    }

    @Override
    public ToolExecutionJournalEvidence summarize(String runId) {
        long started = entries.values().stream()
                .filter(entry -> entry.runId().equals(runId))
                .filter(entry -> entry.status() == ToolExecutionJournalEntry.Status.STARTED)
                .count();
        long completed = entries.values().stream()
                .filter(entry -> entry.runId().equals(runId))
                .filter(entry -> entry.status() == ToolExecutionJournalEntry.Status.COMPLETED)
                .count();
        return new ToolExecutionJournalEvidence(
                Math.toIntExact(started), Math.toIntExact(completed));
    }

    @Override
    public ToolExecutionJournalEntry complete(
            String runId,
            String toolCallId,
            String executionToken,
            ToolResult result,
            Instant completedAt
    ) {
        Key key = new Key(runId, toolCallId);
        ToolExecutionJournalEntry[] completed = new ToolExecutionJournalEntry[1];
        entries.computeIfPresent(key, (ignored, current) -> {
            if (current.status() != ToolExecutionJournalEntry.Status.STARTED
                    || !current.executionToken().equals(executionToken)) {
                return current;
            }
            completed[0] = current.complete(result, completedAt);
            return completed[0];
        });
        return completed[0];
    }

    private record Key(String runId, String toolCallId) {
    }
}
