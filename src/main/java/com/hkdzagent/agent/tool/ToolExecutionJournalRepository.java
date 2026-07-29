package com.hkdzagent.agent.tool;

import java.time.Instant;

public interface ToolExecutionJournalRepository {

    Reservation reserve(ToolExecutionJournalEntry candidate);

    ToolExecutionJournalEntry find(String runId, String toolCallId);

    ToolExecutionJournalEvidence summarize(String runId);

    ToolExecutionJournalEntry complete(
            String runId,
            String toolCallId,
            String executionToken,
            ToolResult result,
            Instant completedAt
    );

    record Reservation(boolean acquired, ToolExecutionJournalEntry entry) {
        public Reservation {
            if (entry == null) {
                throw new IllegalArgumentException("entry must not be null");
            }
        }
    }
}
