package com.hkdzagent.agent.tool;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class ToolExecutionPipeline {

    private final ToolInvocationValidator validator;
    private final ToolPolicyEngine policyEngine;
    private final ToolExecutionJournalRepository journalRepository;
    private final Clock clock;

    public ToolExecutionPipeline(ToolInvocationValidator validator, ToolPolicyEngine policyEngine) {
        this(validator, policyEngine, new InMemoryToolExecutionJournalRepository(), Clock.systemUTC());
    }

    public ToolExecutionPipeline(
            ToolInvocationValidator validator,
            ToolPolicyEngine policyEngine,
            ToolExecutionJournalRepository journalRepository,
            Clock clock
    ) {
        this.validator = validator;
        this.policyEngine = policyEngine;
        this.journalRepository = journalRepository;
        this.clock = clock;
    }

    public ToolPipelineResult invoke(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        PreparedInvocation prepared = prepare(context, toolName, argumentsJson);
        if (prepared.result().status() != ToolPipelineResult.Status.READY) {
            return prepared.result();
        }
        return execute(prepared.invocation());
    }

    public ToolPipelineResult assess(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        return prepare(context, toolName, argumentsJson).result();
    }

    public ToolPipelineResult invokeApproved(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson,
            String approvedToolVersion,
            String approvedArgumentsHash
    ) {
        PreparedInvocation prepared = prepare(context, toolName, argumentsJson);
        ToolPipelineResult assessment = prepared.result();
        if (assessment.status() == ToolPipelineResult.Status.REJECTED
                || assessment.status() == ToolPipelineResult.Status.FAILED) {
            return assessment;
        }
        if (!assessment.toolVersion().equals(approvedToolVersion)) {
            return ToolPipelineResult.from(
                    prepared.invocation(), ToolPipelineResult.Status.REJECTED, null,
                    "approved tool version does not match registered tool version");
        }
        if (!assessment.argumentsHash().equals(approvedArgumentsHash)) {
            return ToolPipelineResult.from(
                    prepared.invocation(), ToolPipelineResult.Status.REJECTED, null,
                    "approved arguments hash does not match invocation arguments");
        }
        return execute(prepared.invocation());
    }

    private PreparedInvocation prepare(
            ToolInvocationContext context,
            String toolName,
            String argumentsJson
    ) {
        ValidatedToolInvocation<?, ?> invocation;
        try {
            invocation = validator.validate(context, toolName, argumentsJson);
        } catch (ToolInvocationValidationException exception) {
            return new PreparedInvocation(null,
                    ToolPipelineResult.invalid(toolName, exception.getMessage()));
        }

        ToolPolicyDecision decision = policyEngine.evaluate(invocation);
        if (decision.outcome() == ToolPolicyDecision.Outcome.DENY) {
            return new PreparedInvocation(invocation, ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.REJECTED, null, decision.reason()));
        }
        if (decision.outcome() == ToolPolicyDecision.Outcome.APPROVAL_REQUIRED) {
            return new PreparedInvocation(invocation, ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.APPROVAL_REQUIRED, null, decision.reason()));
        }

        return new PreparedInvocation(invocation, ToolPipelineResult.ready(invocation));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ToolPipelineResult execute(ValidatedToolInvocation invocation) {
        Instant startedAt = clock.instant();
        ToolExecutionJournalEntry candidate = ToolExecutionJournalEntry.started(
                invocation.context(),
                invocation.metadata().name(),
                invocation.metadata().version(),
                invocation.argumentsHash(),
                UUID.randomUUID().toString(),
                startedAt);
        ToolExecutionJournalRepository.Reservation reservation =
                journalRepository.reserve(candidate);
        ToolExecutionJournalEntry journalEntry = reservation.entry();
        if (!candidate.hasSameBinding(journalEntry)) {
            return ToolPipelineResult.from(
                    invocation, ToolPipelineResult.Status.REJECTED, null,
                    "tool call id is already bound to a different invocation");
        }
        if (!reservation.acquired()) {
            if (journalEntry.status() == ToolExecutionJournalEntry.Status.COMPLETED) {
                return fromJournal(invocation, journalEntry);
            }
            return ToolPipelineResult.executionUncertain(
                    invocation,
                    "tool execution was previously started but has no durable result");
        }

        ToolResult result;
        try {
            result = (ToolResult) invocation.tool().execute(invocation.input());
            if (result == null) {
                result = ToolResult.failure("tool returned no result");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            result = ToolResult.failure("tool execution was interrupted");
        } catch (Exception exception) {
            result = ToolResult.failure("tool execution failed: " + exception.getMessage());
        }

        ToolExecutionJournalEntry completed = journalRepository.complete(
                candidate.runId(), candidate.toolCallId(), candidate.executionToken(),
                result, clock.instant());
        if (completed == null) {
            return ToolPipelineResult.executionUncertain(
                    invocation,
                    "tool finished but its durable result could not be committed");
        }
        return fromJournal(invocation, completed);
    }

    private ToolPipelineResult fromJournal(
            ValidatedToolInvocation<?, ?> invocation,
            ToolExecutionJournalEntry entry
    ) {
        ToolResult result = new ToolResult(entry.resultStatus(), entry.resultMessage());
        ToolPipelineResult.Status status = switch (result.status()) {
            case SUCCESS -> ToolPipelineResult.Status.COMPLETED;
            case REJECTED -> ToolPipelineResult.Status.REJECTED;
            case FAILED -> ToolPipelineResult.Status.FAILED;
        };
        return ToolPipelineResult.from(
                invocation, status, result,
                status == ToolPipelineResult.Status.COMPLETED ? null : result.message());
    }

    private record PreparedInvocation(
            ValidatedToolInvocation<?, ?> invocation,
            ToolPipelineResult result
    ) {
    }
}
