package com.hkdzagent.agent.trace;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentTraceRecorder {

    private final AgentTraceRepository repository;
    private final AgentTraceSanitizer sanitizer;

    public AgentTraceRecorder(AgentTraceRepository repository, AgentTraceSanitizer sanitizer) {
        this.repository = repository;
        this.sanitizer = sanitizer;
    }

    public AgentTrace startTrace(String traceId, String sessionId, String userMessage) {
        return repository.save(new AgentTrace(traceId, sessionId, sanitizer.preview(userMessage)));
    }

    public void recordModelRequest(String traceId, int step, Map<String, Object> metadata) {
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.MODEL_REQUEST,
                step,
                null,
                null,
                null,
                null,
                null,
                null,
                sanitizer.sanitizeMetadata(metadata)
        ));
    }

    public void recordModelResponse(String traceId, int step, int statusCode, Duration duration, Map<String, Object> metadata) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (metadata != null) {
            enriched.putAll(metadata);
        }
        enriched.put("statusCode", statusCode);
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.MODEL_RESPONSE,
                step,
                null,
                null,
                null,
                null,
                null,
                durationMs(duration),
                sanitizer.sanitizeMetadata(enriched)
        ));
    }

    public void recordToolCall(String traceId, int step, String toolName, String arguments) {
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.TOOL_CALL,
                step,
                toolName,
                null,
                null,
                sanitizer.preview(arguments),
                null,
                null,
                Map.of()
        ));
    }

    public void recordToolObservation(String traceId, int step, String toolName, boolean success, String content, Duration duration) {
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.TOOL_OBSERVATION,
                step,
                toolName,
                success,
                sanitizer.preview(content),
                null,
                null,
                durationMs(duration),
                Map.of()
        ));
    }

    public void recordFinalAnswer(String traceId, String finalAnswer) {
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.FINAL_ANSWER,
                0,
                null,
                null,
                sanitizer.preview(finalAnswer),
                null,
                null,
                null,
                Map.of()
        ));
    }

    public void recordError(String traceId, int step, String errorMessage, Duration duration) {
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.ERROR,
                step,
                null,
                false,
                null,
                null,
                sanitizer.preview(errorMessage),
                durationMs(duration),
                Map.of()
        ));
    }

    public void recordStepLimit(String traceId, int step, String message) {
        addEvent(traceId, new AgentTraceEvent(
                traceId,
                TraceEventType.STEP_LIMIT,
                step,
                null,
                false,
                sanitizer.preview(message),
                null,
                null,
                null,
                Map.of()
        ));
    }

    public void finishTrace(String traceId, String status) {
        repository.finish(traceId, TraceStatus.valueOf(status));
    }

    private void addEvent(String traceId, AgentTraceEvent event) {
        repository.addEvent(traceId, event);
    }

    private Long durationMs(Duration duration) {
        return duration == null ? null : Math.max(0L, duration.toMillis());
    }
}
