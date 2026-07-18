package com.hkdzagent.agent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.model.ChatRequest;
import com.hkdzagent.agent.model.ChatResponse;
import com.hkdzagent.agent.trace.AgentTrace;
import com.hkdzagent.agent.trace.AgentTraceEvent;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class AgentController {

    private final LLMClient llmClient;
    private final AgentTraceSanitizer fallbackSanitizer = new AgentTraceSanitizer(120);
    private ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryAgentTraceRepository traceRepository = new InMemoryAgentTraceRepository();
    private AgentTraceRecorder traceRecorder = new AgentTraceRecorder(traceRepository, fallbackSanitizer);
    private ToolConfirmationService confirmationService = new ToolConfirmationService(fallbackSanitizer);

    public AgentController(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    @PostMapping("/api/agent/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = llmClient.askWithTools(request.message(), request.sessionId());
        return new ChatResponse(answer);
    }

    @PostMapping(value = "/api/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        String traceId = UUID.randomUUID().toString();
        String sessionId = normalizeSessionId(request.sessionId());
        String message = request.message() == null ? "" : request.message();
        traceRecorder.startTrace(traceId, sessionId, message);

        return Flux.defer(() -> runAgentStream(traceId, sessionId, message))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/api/agent/traces/{traceId}")
    public ResponseEntity<AgentTraceView> trace(@PathVariable String traceId) {
        AgentTrace trace = traceRepository.findByTraceId(traceId);
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(AgentTraceView.from(trace));
    }

    @GetMapping("/api/agent/traces")
    public List<AgentTraceView> recentTraces(@RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return traceRepository.findRecent(safeLimit).stream()
                .map(AgentTraceView::from)
                .toList();
    }

    @GetMapping("/api/agent/tool-confirmations")
    public List<ToolConfirmation> pendingConfirmations(@RequestParam(defaultValue = "default") String sessionId) {
        return confirmationService.findPendingBySessionId(sessionId);
    }

    @PostMapping("/api/agent/tool-confirmations/{confirmationId}/approve")
    public ToolConfirmation approveConfirmation(@PathVariable String confirmationId) {
        return confirmationService.approve(confirmationId);
    }

    @PostMapping("/api/agent/tool-confirmations/{confirmationId}/reject")
    public ToolConfirmation rejectConfirmation(
            @PathVariable String confirmationId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body == null ? null : body.get("reason");
        return confirmationService.reject(confirmationId, reason);
    }

    @Autowired(required = false)
    void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setTraceRepository(InMemoryAgentTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @Autowired(required = false)
    void setTraceRecorder(AgentTraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Autowired(required = false)
    void setConfirmationService(ToolConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    private Flux<String> runAgentStream(String traceId, String sessionId, String message) {
        LinkedHashMap<String, Object> started = payload(traceId, sessionId);
        started.put("status", "RUNNING");

        try {
            long startedNanos = System.nanoTime();
            String answer = llmClient.askWithTools(message, sessionId);
            Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
            traceRecorder.recordFinalAnswer(traceId, answer);
            traceRecorder.finishTrace(traceId, "COMPLETED");

            LinkedHashMap<String, Object> token = payload(traceId, sessionId);
            token.put("content", answer);

            LinkedHashMap<String, Object> finished = payload(traceId, sessionId);
            finished.put("answer", answer);
            finished.put("status", "COMPLETED");
            finished.put("durationMs", duration.toMillis());

            return Flux.just(
                    sse("started", started),
                    sse("token", token),
                    sse("final", finished)
            );
        } catch (Exception e) {
            traceRecorder.recordError(traceId, 0, e.getMessage(), Duration.ZERO);
            traceRecorder.finishTrace(traceId, "FAILED");

            LinkedHashMap<String, Object> failed = payload(traceId, sessionId);
            failed.put("status", "FAILED");
            failed.put("error", e.getMessage());
            return Flux.just(
                    sse("started", started),
                    sse("error", failed)
            );
        }
    }

    private String sse(String event, Map<String, Object> data) {
        return "event: " + event + "\n" +
                "data: " + writeJson(data) + "\n\n";
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"failed to serialize console event\"}";
        }
    }

    private LinkedHashMap<String, Object> payload(String traceId, String sessionId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("sessionId", sessionId);
        return payload;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }

    private record AgentTraceView(
            String traceId,
            String sessionId,
            String userMessage,
            String status,
            Long durationMs,
            List<AgentTraceEvent> events
    ) {

        private static AgentTraceView from(AgentTrace trace) {
            return new AgentTraceView(
                    trace.traceId(),
                    trace.sessionId(),
                    trace.userMessage(),
                    trace.status().name(),
                    trace.durationMs(),
                    trace.events()
            );
        }
    }
}
