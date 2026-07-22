package com.hkdzagent.agent.tool;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ToolExecutionJournalEntry(
        String runId,
        String toolCallId,
        String toolName,
        String toolVersion,
        String argumentsHash,
        Status status,
        ToolResult.Status resultStatus,
        String resultMessage,
        String executionToken,
        Instant startedAt,
        Instant completedAt
) {
    public enum Status {
        STARTED,
        COMPLETED
    }

    public ToolExecutionJournalEntry {
        requireUuid(runId, "runId");
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        requireText(toolVersion, "toolVersion");
        if (argumentsHash == null || !argumentsHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("argumentsHash must be a lowercase SHA-256 hash");
        }
        Objects.requireNonNull(status, "status must not be null");
        requireUuid(executionToken, "executionToken");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (status == Status.STARTED
                && (resultStatus != null || resultMessage != null || completedAt != null)) {
            throw new IllegalArgumentException("started execution must not contain a result");
        }
        if (status == Status.COMPLETED
                && (resultStatus == null || resultMessage == null || completedAt == null)) {
            throw new IllegalArgumentException("completed execution must contain its result");
        }
    }

    public static ToolExecutionJournalEntry started(
            ToolInvocationContext context,
            String toolName,
            String toolVersion,
            String argumentsHash,
            String executionToken,
            Instant startedAt
    ) {
        return new ToolExecutionJournalEntry(
                context.runId(), context.toolCallId(), toolName, toolVersion, argumentsHash,
                Status.STARTED, null, null, executionToken, startedAt, null);
    }

    public boolean hasSameBinding(ToolExecutionJournalEntry other) {
        return runId.equals(other.runId)
                && toolCallId.equals(other.toolCallId)
                && toolName.equals(other.toolName)
                && toolVersion.equals(other.toolVersion)
                && argumentsHash.equals(other.argumentsHash);
    }

    public ToolExecutionJournalEntry complete(ToolResult result, Instant completedAt) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (status != Status.STARTED) {
            throw new IllegalStateException("tool execution is already completed");
        }
        return new ToolExecutionJournalEntry(
                runId, toolCallId, toolName, toolVersion, argumentsHash,
                Status.COMPLETED, result.status(), result.message(), executionToken,
                startedAt, completedAt);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireUuid(String value, String field) {
        requireText(value, field);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }
}
