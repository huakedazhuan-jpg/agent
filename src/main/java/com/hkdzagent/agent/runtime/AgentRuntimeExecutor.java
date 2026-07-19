package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

public class AgentRuntimeExecutor {

    private final AgentRuntimeService runtimeService;
    private final LLMClient llmClient;
    private final AgentTraceRecorder traceRecorder;
    private final AgentTraceSanitizer sanitizer;
    private final AgentApprovalPauseService approvalPauseService;
    private final ToolConfirmationProperties confirmationProperties;

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties
    ) {
        this.runtimeService = runtimeService;
        this.llmClient = llmClient;
        this.traceRecorder = traceRecorder;
        this.sanitizer = sanitizer;
        this.approvalPauseService = approvalPauseService;
        this.confirmationProperties = confirmationProperties;
    }

    public void execute(String runId, String workerId, Consumer<AgentRunEvent> eventConsumer) {
        AgentRun run = requireRun(runId);
        AgentRunClaim claim = runtimeService.claim(runId, workerId);
        if (claim == null) {
            throw new IllegalStateException("agent run is not available for worker " + workerId);
        }
        emit(runId, AgentRunEventType.RUN_STARTED, Map.of("workerId", workerId), eventConsumer);

        RuntimeObserver observer = new RuntimeObserver(run, workerId, eventConsumer);
        try {
            AgentLoopResult result = llmClient.runWithTools(
                    run.userMessage(), run.conversationId(), run.traceId(), observer);
            handleResult(run, workerId, result, eventConsumer);
        } catch (Exception exception) {
            failSafely(run, workerId, exception);
            emit(runId, AgentRunEventType.RUN_FAILED,
                    Map.of("error", sanitizer.preview(safeMessage(exception))), eventConsumer);
        }
    }

    public void resumeApproved(
            String runId,
            String approvalId,
            String workerId,
            Consumer<AgentRunEvent> eventConsumer
    ) {
        AgentRun run = runtimeService.resumeApproval(runId, approvalId, workerId);
        emit(runId, AgentRunEventType.RUN_STARTED,
                Map.of("workerId", workerId, "resumed", true, "approvalId", approvalId), eventConsumer);
        RuntimeObserver observer = new RuntimeObserver(run, workerId, eventConsumer);
        try {
            AgentLoopResult result = llmClient.resumeWithApprovedTool(run.checkpointJson(), observer);
            handleResult(run, workerId, result, eventConsumer);
        } catch (Exception exception) {
            failSafely(run, workerId, exception);
            emit(runId, AgentRunEventType.RUN_FAILED,
                    Map.of("error", sanitizer.preview(safeMessage(exception))), eventConsumer);
        }
    }

    private void handleResult(
            AgentRun run,
            String workerId,
            AgentLoopResult result,
            Consumer<AgentRunEvent> eventConsumer
    ) {
        if (result.status() == AgentLoopResult.Status.WAITING_APPROVAL) {
            return;
        }
        if (result.status() != AgentLoopResult.Status.COMPLETED) {
            runtimeService.fail(run.runId(), workerId, sanitizer.preview(result.finalAnswer()));
            traceRecorder.recordError(run.traceId(), 0, result.finalAnswer(), Duration.ZERO);
            traceRecorder.finishTrace(run.traceId(), "FAILED");
            emit(run.runId(), AgentRunEventType.RUN_FAILED,
                    Map.of("error", sanitizer.preview(result.finalAnswer())), eventConsumer);
            return;
        }
        runtimeService.complete(run.runId(), workerId, result.finalAnswer());
        traceRecorder.recordFinalAnswer(run.traceId(), result.finalAnswer());
        traceRecorder.finishTrace(run.traceId(), "COMPLETED");
        emit(run.runId(), AgentRunEventType.RUN_COMPLETED,
                Map.of("answer", result.finalAnswer()), eventConsumer);
    }

    private void failSafely(AgentRun run, String workerId, Exception exception) {
        try {
            runtimeService.fail(run.runId(), workerId, sanitizer.preview(safeMessage(exception)));
        } catch (Exception ignored) {
            // Preserve the original execution failure if another worker already changed the run.
        }
        traceRecorder.recordError(run.traceId(), 0, safeMessage(exception), Duration.ZERO);
        traceRecorder.finishTrace(run.traceId(), "FAILED");
    }

    private AgentRun requireRun(String runId) {
        AgentRun run = runtimeService.find(runId);
        if (run == null) {
            throw new IllegalArgumentException("agent run not found: " + runId);
        }
        return run;
    }

    private AgentRunEvent emit(
            String runId,
            AgentRunEventType type,
            Object payload,
            Consumer<AgentRunEvent> eventConsumer
    ) {
        AgentRunEvent event = runtimeService.appendEvent(runId, type, payload);
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
        return event;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private final class RuntimeObserver implements AgentExecutionObserver {

        private final AgentRun run;
        private final String workerId;
        private final Consumer<AgentRunEvent> consumer;

        private RuntimeObserver(AgentRun run, String workerId, Consumer<AgentRunEvent> consumer) {
            this.run = run;
            this.workerId = workerId;
            this.consumer = consumer;
        }

        private String runId() {
            return run.runId();
        }

        @Override
        public void modelStarted(int step) {
            runtimeService.checkpoint(runId(), workerId, step, Map.of("phase", "MODEL", "step", step));
            traceRecorder.recordModelRequest(run.traceId(), step, Map.of("runtimeRunId", run.runId()));
            emit(runId(), AgentRunEventType.MODEL_STARTED, Map.of("step", step), consumer);
        }

        @Override
        public void tokenDelta(int step, String delta) {
            emit(runId(), AgentRunEventType.TOKEN_DELTA,
                    Map.of("step", step, "delta", delta), consumer);
        }

        @Override
        public void modelCompleted(int step) {
            emit(runId(), AgentRunEventType.MODEL_COMPLETED, Map.of("step", step), consumer);
        }

        @Override
        public void toolCallRequested(int step, String toolName, String arguments) {
            traceRecorder.recordToolCall(run.traceId(), step, toolName, arguments);
            emit(runId(), AgentRunEventType.TOOL_CALL_REQUESTED,
                    Map.of("step", step, "toolName", toolName,
                            "arguments", sanitizer.preview(arguments)), consumer);
        }

        @Override
        public boolean requiresApproval(int step, String toolName, String arguments) {
            return confirmationProperties.requiresApproval(toolName);
        }

        @Override
        public void approvalRequired(
                int step, String toolName, String arguments, String checkpointJson
        ) {
            AgentRunEvent event = approvalPauseService.pause(
                    run, workerId, step, toolName, arguments, checkpointJson);
            if (consumer != null) {
                consumer.accept(event);
            }
        }

        @Override
        public void toolStarted(int step, String toolName) {
            emit(runId(), AgentRunEventType.TOOL_STARTED,
                    Map.of("step", step, "toolName", toolName), consumer);
        }

        @Override
        public void toolCompleted(int step, String toolName, boolean success, String observation) {
            traceRecorder.recordToolObservation(
                    run.traceId(), step, toolName, success, observation, Duration.ZERO);
            emit(runId(), AgentRunEventType.TOOL_COMPLETED,
                    Map.of("step", step, "toolName", toolName, "success", success,
                            "observation", observation == null ? "" : sanitizer.preview(observation)), consumer);
        }
    }
}
