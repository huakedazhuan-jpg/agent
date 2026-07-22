package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.ai.AgentExecutionObserver;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.loop.AgentObservation;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolPipelineResult;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;

import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Consumer;

public class AgentRuntimeExecutor {

    private final AgentRuntimeService runtimeService;
    private final LLMClient llmClient;
    private final AgentTraceRecorder traceRecorder;
    private final AgentTraceSanitizer sanitizer;
    private final AgentApprovalPauseService approvalPauseService;
    private final ToolConfirmationProperties confirmationProperties;
    private final ToolExecutionPipeline toolExecutionPipeline;
    private final ApprovedToolExecutionService approvedToolExecutionService;
    private final AgentCompletionService completionService;
    private final AgentFailureService failureService;
    private final AgentRunLeaseHeartbeatFactory heartbeatFactory;

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties
    ) {
        this(runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties, null, null, null, null, null);
    }

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties,
            ToolExecutionPipeline toolExecutionPipeline
    ) {
        this(runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties,
                toolExecutionPipeline, null, null, null, null);
    }

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties,
            ToolExecutionPipeline toolExecutionPipeline,
            ApprovedToolExecutionService approvedToolExecutionService
    ) {
        this(runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties,
                toolExecutionPipeline, approvedToolExecutionService, null, null, null);
    }

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties,
            ToolExecutionPipeline toolExecutionPipeline,
            ApprovedToolExecutionService approvedToolExecutionService,
            AgentCompletionService completionService
    ) {
        this(runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties, toolExecutionPipeline,
                approvedToolExecutionService, completionService, null, null);
    }

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties,
            ToolExecutionPipeline toolExecutionPipeline,
            ApprovedToolExecutionService approvedToolExecutionService,
            AgentCompletionService completionService,
            AgentFailureService failureService
    ) {
        this(runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties, toolExecutionPipeline,
                approvedToolExecutionService, completionService, failureService, null);
    }

    public AgentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties,
            ToolExecutionPipeline toolExecutionPipeline,
            ApprovedToolExecutionService approvedToolExecutionService,
            AgentCompletionService completionService,
            AgentFailureService failureService,
            AgentRunLeaseHeartbeatFactory heartbeatFactory
    ) {
        this.runtimeService = runtimeService;
        this.llmClient = llmClient;
        this.traceRecorder = traceRecorder;
        this.sanitizer = sanitizer;
        this.approvalPauseService = approvalPauseService;
        this.confirmationProperties = confirmationProperties;
        this.toolExecutionPipeline = toolExecutionPipeline;
        this.approvedToolExecutionService = approvedToolExecutionService;
        this.completionService = completionService;
        this.failureService = failureService;
        this.heartbeatFactory = heartbeatFactory;
    }

    public void execute(String runId, String workerId, Consumer<AgentRunEvent> eventConsumer) {
        AgentRunClaim claim = runtimeService.claim(runId, workerId);
        if (claim == null) {
            throw new IllegalStateException("agent run is not available for worker " + workerId);
        }
        executeClaimed(claim.run(), workerId, eventConsumer);
    }

    public void executeClaimed(
            AgentRun run,
            String workerId,
            Consumer<AgentRunEvent> eventConsumer
    ) {
        if (run == null) {
            throw new IllegalArgumentException("claimed agent run must not be null");
        }
        try (AgentRunLeaseHeartbeat heartbeat = heartbeat(run, workerId)) {
            emitWorker(run, workerId, AgentRunEventType.RUN_STARTED,
                    Map.of("workerId", workerId), eventConsumer);
            RuntimeObserver observer = new RuntimeObserver(run, workerId, heartbeat, eventConsumer);
            AgentLoopResult result = llmClient.runWithTools(
                    run.userMessage(), run.conversationId(), run.traceId(), observer);
            assertHeartbeat(heartbeat);
            handleResult(run, workerId, result, eventConsumer);
        } catch (AgentRunLeaseLostException exception) {
            throw exception;
        } catch (Exception exception) {
            AgentRunEvent event = failExecution(
                    run, workerId, sanitizer.preview(safeMessage(exception)));
            accept(event, eventConsumer);
            recordFailureTrace(run, safeMessage(exception));
        }
    }

    public void resumeApproved(
            String runId,
            String approvalId,
            String workerId,
            Consumer<AgentRunEvent> eventConsumer
    ) {
        AgentRun run = runtimeService.resumeApproval(runId, approvalId, workerId);
        try (AgentRunLeaseHeartbeat heartbeat = heartbeat(run, workerId)) {
            emitWorker(run, workerId, AgentRunEventType.RUN_STARTED,
                    Map.of("workerId", workerId, "resumed", true, "approvalId", approvalId),
                    eventConsumer);
            RuntimeObserver observer = new RuntimeObserver(run, workerId, heartbeat, eventConsumer);
            if (approvedToolExecutionService == null) {
                throw new IllegalStateException("approved tool execution service is not configured");
            }
            ApprovedToolExecution approvedExecution =
                    approvedToolExecutionService.execute(run, approvalId);
            AgentLoopResult result = llmClient.resumeWithApprovedTool(
                    run.checkpointJson(), approvedExecution.observation(), observer);
            assertHeartbeat(heartbeat);
            handleResult(run, workerId, result, eventConsumer);
        } catch (AgentRunLeaseLostException exception) {
            throw exception;
        } catch (Exception exception) {
            AgentRunEvent event = failExecution(
                    run, workerId, sanitizer.preview(safeMessage(exception)));
            accept(event, eventConsumer);
            recordFailureTrace(run, safeMessage(exception));
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
            AgentRunEvent event = failExecution(
                    run, workerId, sanitizer.preview(result.finalAnswer()));
            accept(event, eventConsumer);
            recordFailureTrace(run, result.finalAnswer());
            return;
        }
        AgentRunEvent completedEvent;
        if (completionService == null) {
            runtimeService.complete(
                    run.runId(), workerId, run.leaseEpoch(), result.finalAnswer());
            completedEvent = runtimeService.appendSystemEvent(
                    run.runId(), AgentRunEventType.RUN_COMPLETED,
                    Map.of("answer", result.finalAnswer()));
        } else {
            completedEvent = completionService.complete(
                    run.runId(), workerId, run.leaseEpoch(), result.finalAnswer()).event();
        }
        traceRecorder.recordFinalAnswer(run.traceId(), result.finalAnswer());
        traceRecorder.finishTrace(run.traceId(), "COMPLETED");
        accept(completedEvent, eventConsumer);
    }

    private AgentRunEvent failExecution(AgentRun run, String workerId, String error) {
        if (failureService != null) {
            return failureService.fail(run.runId(), workerId, run.leaseEpoch(), error);
        }
        String normalized = error == null || error.isBlank()
                ? "agent execution failed"
                : error;
        runtimeService.fail(run.runId(), workerId, run.leaseEpoch(), normalized);
        return runtimeService.appendSystemEvent(
                run.runId(), AgentRunEventType.RUN_FAILED, Map.of("error", normalized));
    }

    private void recordFailureTrace(AgentRun run, String error) {
        try {
            traceRecorder.recordError(
                    run.traceId(), 0,
                    error == null ? "agent execution failed" : error,
                    Duration.ZERO);
            traceRecorder.finishTrace(run.traceId(), "FAILED");
        } catch (Exception ignored) {
            // Durable run and notification state must not be retried because tracing failed.
        }
    }

    private void accept(AgentRunEvent event, Consumer<AgentRunEvent> eventConsumer) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    private AgentRunLeaseHeartbeat heartbeat(AgentRun run, String workerId) {
        return heartbeatFactory == null
                ? null
                : heartbeatFactory.start(run.runId(), workerId, run.leaseEpoch());
    }

    private void assertHeartbeat(AgentRunLeaseHeartbeat heartbeat) {
        if (heartbeat != null) {
            heartbeat.assertActive();
        }
    }

    private AgentRunEvent emitWorker(
            AgentRun run,
            String workerId,
            AgentRunEventType type,
            Object payload,
            Consumer<AgentRunEvent> eventConsumer
    ) {
        AgentRunEvent event = runtimeService.appendWorkerEvent(
                run.runId(), workerId, run.leaseEpoch(), type, payload);
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
        private final AgentRunLeaseHeartbeat heartbeat;
        private final Consumer<AgentRunEvent> consumer;
        private final Map<String, PendingToolAssessment> pendingAssessments = new HashMap<>();

        private RuntimeObserver(
                AgentRun run,
                String workerId,
                AgentRunLeaseHeartbeat heartbeat,
                Consumer<AgentRunEvent> consumer
        ) {
            this.run = run;
            this.workerId = workerId;
            this.heartbeat = heartbeat;
            this.consumer = consumer;
        }

        private String runId() {
            return run.runId();
        }

        @Override
        public void modelStarted(int step) {
            assertHeartbeat(heartbeat);
            runtimeService.checkpoint(
                    runId(), workerId, run.leaseEpoch(), step,
                    Map.of("phase", "MODEL", "step", step));
            traceRecorder.recordModelRequest(run.traceId(), step, Map.of("runtimeRunId", run.runId()));
            emitWorker(run, workerId, AgentRunEventType.MODEL_STARTED,
                    Map.of("step", step), consumer);
        }

        @Override
        public void tokenDelta(int step, String delta) {
            assertHeartbeat(heartbeat);
            emitWorker(run, workerId, AgentRunEventType.TOKEN_DELTA,
                    Map.of("step", step, "delta", delta), consumer);
        }

        @Override
        public void modelCompleted(int step) {
            assertHeartbeat(heartbeat);
            emitWorker(run, workerId, AgentRunEventType.MODEL_COMPLETED,
                    Map.of("step", step), consumer);
        }

        @Override
        public void toolCallRequested(int step, String toolName, String arguments) {
            assertHeartbeat(heartbeat);
            traceRecorder.recordToolCall(run.traceId(), step, toolName, arguments);
            emitWorker(run, workerId, AgentRunEventType.TOOL_CALL_REQUESTED,
                    Map.of("step", step, "toolName", toolName,
                            "arguments", sanitizer.preview(arguments)), consumer);
        }

        @Override
        public boolean requiresApproval(int step, String toolName, String arguments) {
            return confirmationProperties.requiresApproval(toolName);
        }

        @Override
        public boolean requiresApproval(
                int step, String toolCallId, String toolName, String arguments
        ) {
            assertHeartbeat(heartbeat);
            if (toolExecutionPipeline == null) {
                return requiresApproval(step, toolName, arguments);
            }
            ToolPipelineResult assessment = toolExecutionPipeline.assess(
                    new ToolInvocationContext(
                            run.ownerKey(), run.runId(), run.traceId(), toolCallId, Set.of()),
                    toolName,
                    arguments
            );
            if (assessment.status() == ToolPipelineResult.Status.READY
                    || assessment.status() == ToolPipelineResult.Status.APPROVAL_REQUIRED) {
                pendingAssessments.put(
                        toolCallId, new PendingToolAssessment(arguments, assessment));
            } else {
                pendingAssessments.remove(toolCallId);
            }
            return switch (assessment.status()) {
                case READY -> false;
                case APPROVAL_REQUIRED -> true;
                case REJECTED, FAILED -> throw new IllegalArgumentException(
                        "tool invocation rejected: " + assessment.reason());
                case COMPLETED -> throw new IllegalStateException(
                        "tool assessment must not execute the tool");
            };
        }

        @Override
        public void approvalRequired(
                int step, String toolName, String arguments, String checkpointJson
        ) {
            assertHeartbeat(heartbeat);
            AgentRunEvent event = approvalPauseService.pause(
                    run, workerId, step, toolName, arguments, checkpointJson);
            if (consumer != null) {
                consumer.accept(event);
            }
        }

        @Override
        public void approvalRequired(
                int step,
                String toolCallId,
                String toolName,
                String arguments,
                String checkpointJson
        ) {
            assertHeartbeat(heartbeat);
            if (toolExecutionPipeline == null) {
                approvalRequired(step, toolName, arguments, checkpointJson);
                return;
            }
            PendingToolAssessment pending = pendingAssessments.remove(toolCallId);
            ToolPipelineResult assessment = pending == null ? null : pending.assessment();
            if (assessment == null
                    || assessment.status() != ToolPipelineResult.Status.APPROVAL_REQUIRED
                    || !toolName.equals(assessment.toolName())
                    || !arguments.equals(pending.arguments())) {
                throw new IllegalStateException(
                        "approval request does not match a pending tool assessment");
            }
            ToolInvocationContext context = new ToolInvocationContext(
                    run.ownerKey(), run.runId(), run.traceId(), toolCallId, Set.of());
            AgentRunEvent event = approvalPauseService.pauseBoundInvocation(
                    run, workerId, step, context, arguments, assessment, checkpointJson);
            if (consumer != null) {
                consumer.accept(event);
            }
        }

        @Override
        public AgentObservation executeTool(
                int step,
                String toolCallId,
                String toolName,
                String arguments
        ) {
            assertHeartbeat(heartbeat);
            if (toolExecutionPipeline == null) {
                return null;
            }
            PendingToolAssessment pending = pendingAssessments.remove(toolCallId);
            if (pending == null
                    || pending.assessment().status() != ToolPipelineResult.Status.READY
                    || !toolName.equals(pending.assessment().toolName())
                    || !arguments.equals(pending.arguments())) {
                throw new SecurityException(
                        "tool execution does not match a ready tool assessment");
            }
            ToolInvocationContext context = new ToolInvocationContext(
                    run.ownerKey(), run.runId(), run.traceId(), toolCallId, Set.of());
            ToolPipelineResult result = toolExecutionPipeline.invoke(
                    context, toolName, arguments);
            if (!pending.assessment().toolVersion().equals(result.toolVersion())
                    || !pending.assessment().argumentsHash().equals(result.argumentsHash())) {
                throw new SecurityException(
                        "tool execution binding changed after assessment");
            }
            if (result.status() == ToolPipelineResult.Status.READY
                    || result.status() == ToolPipelineResult.Status.APPROVAL_REQUIRED) {
                throw new IllegalStateException(
                        "tool policy changed after the execution assessment");
            }
            if (result.toolResult() == null) {
                throw new SecurityException("tool invocation was rejected: " + result.reason());
            }
            return new AgentObservation(
                    toolName,
                    result.toolResult().message(),
                    result.status() == ToolPipelineResult.Status.COMPLETED);
        }

        @Override
        public void toolStarted(int step, String toolName) {
            assertHeartbeat(heartbeat);
            emitWorker(run, workerId, AgentRunEventType.TOOL_STARTED,
                    Map.of("step", step, "toolName", toolName), consumer);
        }

        @Override
        public void toolCompleted(int step, String toolName, boolean success, String observation) {
            assertHeartbeat(heartbeat);
            traceRecorder.recordToolObservation(
                    run.traceId(), step, toolName, success, observation, Duration.ZERO);
            emitWorker(run, workerId, AgentRunEventType.TOOL_COMPLETED,
                    Map.of("step", step, "toolName", toolName, "success", success,
                            "observation", observation == null ? "" : sanitizer.preview(observation)), consumer);
        }

        private record PendingToolAssessment(
                String arguments,
                ToolPipelineResult assessment
        ) {
        }
    }
}
