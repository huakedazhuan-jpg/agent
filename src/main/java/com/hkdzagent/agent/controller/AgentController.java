package com.hkdzagent.agent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.model.ChatRequest;
import com.hkdzagent.agent.model.ChatResponse;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.security.RequestActorResolver;
import com.hkdzagent.agent.runtime.AgentRun;
import com.hkdzagent.agent.runtime.AgentRunEvent;
import com.hkdzagent.agent.runtime.AgentRunEventType;
import com.hkdzagent.agent.runtime.AgentRuntimeExecutor;
import com.hkdzagent.agent.runtime.AgentRuntimeProperties;
import com.hkdzagent.agent.runtime.AgentRuntimeService;
import com.hkdzagent.agent.runtime.AgentRunCoordinator;
import com.hkdzagent.agent.runtime.AgentRunStatus;
import com.hkdzagent.agent.runtime.AgentApprovalOrchestrator;
import com.hkdzagent.agent.runtime.AgentApprovalPauseService;
import com.hkdzagent.agent.runtime.AgentApprovalConflictException;
import com.hkdzagent.agent.runtime.AgentCancellationService;
import com.hkdzagent.agent.runtime.AgentRunCancellation;
import com.hkdzagent.agent.runtime.InMemoryAgentRunRepository;
import com.hkdzagent.agent.trace.AgentTrace;
import com.hkdzagent.agent.trace.AgentTraceEvent;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceRepository;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class AgentController {

    private final LLMClient llmClient;
    private final AgentTraceSanitizer fallbackSanitizer = new AgentTraceSanitizer(120);
    private RequestActorResolver actorResolver = new RequestActorResolver();
    private ObjectMapper objectMapper = new ObjectMapper();
    private AgentTraceRepository traceRepository = new InMemoryAgentTraceRepository();
    private AgentTraceRecorder traceRecorder = new AgentTraceRecorder(traceRepository, fallbackSanitizer);
    private ToolConfirmationService confirmationService = new ToolConfirmationService(fallbackSanitizer);
    private AgentRuntimeService runtimeService;
    private AgentRuntimeExecutor runtimeExecutor;
    private AgentRunCoordinator runCoordinator;
    private AgentApprovalOrchestrator approvalOrchestrator;
    private AgentCancellationService cancellationService;

    public AgentController(LLMClient llmClient) {
        this.llmClient = llmClient;
        this.runtimeService = new AgentRuntimeService(
                new InMemoryAgentRunRepository(),
                new AgentRuntimeProperties(),
                objectMapper,
                Clock.systemUTC()
        );
        this.runtimeExecutor = new AgentRuntimeExecutor(
                runtimeService, llmClient, traceRecorder, fallbackSanitizer,
                new AgentApprovalPauseService(confirmationService, runtimeService, fallbackSanitizer),
                new ToolConfirmationProperties());
        this.runCoordinator = new AgentRunCoordinator(
                runtimeService, runtimeExecutor, traceRecorder);
    }

    @PostMapping("/api/agent/chat")
    public ResponseEntity<?> chat(
            @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        ActorIdentity owner = actorResolver.resolve(authentication);
        String sessionId = normalizeSessionId(request.sessionId());
        AgentRun run = runCoordinator.execute(
                owner,
                sessionId,
                ownedConversationId(owner, sessionId),
                request.message(),
                "http-chat"
        );
        if (run.status() == AgentRunStatus.COMPLETED) {
            return ResponseEntity.ok(new ChatResponse(run.finalAnswer()));
        }
        AgentRunSubmissionResponse response = AgentRunSubmissionResponse.from(run);
        if (run.status() == AgentRunStatus.WAITING_APPROVAL) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
    }

    @PostMapping(value = "/api/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        String traceId = UUID.randomUUID().toString();
        ActorIdentity owner = actorResolver.resolve(authentication);
        String sessionId = normalizeSessionId(request.sessionId());
        String conversationId = ownedConversationId(owner, sessionId);
        String message = request.message() == null ? "" : request.message();
        traceRecorder.startTrace(owner, traceId, sessionId, message);
        AgentRun run = runtimeService.create(owner, sessionId, conversationId, traceId, message);
        AgentRunEvent created = runtimeService.replayEvents(run.runId(), 0).get(0);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            sink.next(sse(created));
            String workerId = "stream-" + UUID.randomUUID();
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    runtimeExecutor.execute(run.runId(), workerId, event -> sink.next(sse(event)));
                    sink.complete();
                } catch (Exception exception) {
                    sink.error(exception);
                }
            });
        });
    }

    @GetMapping(value = "/api/agent/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> replayRunEvents(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long after,
            Authentication authentication
    ) {
        ActorIdentity owner = actorResolver.resolve(authentication);
        if (runtimeService.findOwned(runId, owner) == null) {
            return ResponseEntity.notFound().build();
        }
        Flux<ServerSentEvent<String>> events =
                Flux.fromIterable(runtimeService.replayEvents(runId, Math.max(0, after)))
                .map(this::sse);
        return ResponseEntity.ok(events);
    }

    @GetMapping("/api/agent/runs/{runId}")
    public ResponseEntity<AgentRunView> run(
            @PathVariable String runId,
            Authentication authentication
    ) {
        ActorIdentity owner = actorResolver.resolve(authentication);
        AgentRun run = runtimeService.findOwned(runId, owner);
        return run == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(AgentRunView.from(run));
    }

    @GetMapping("/api/agent/runs")
    public List<AgentRunView> recentRuns(
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication
    ) {
        ActorIdentity owner = actorResolver.resolve(authentication);
        return runtimeService.findRecent(owner, Math.max(1, Math.min(limit, 50))).stream()
                .map(AgentRunView::from)
                .toList();
    }

    @PostMapping("/api/agent/runs/{runId}/cancel")
    public ResponseEntity<?> cancelRun(
            @PathVariable String runId,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication
    ) {
        if (cancellationService == null) {
            throw new IllegalStateException("agent cancellation service is not configured");
        }
        ActorIdentity owner = actorResolver.resolve(authentication);
        String reason = body == null ? null : body.get("reason");
        AgentRunCancellation result = cancellationService.cancel(owner, runId, reason);
        return switch (result.outcome()) {
            case CANCELLED, ALREADY_CANCELLED -> ResponseEntity.ok(
                    AgentRunCancellationResponse.from(result));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case TERMINAL_CONFLICT, CONCURRENT_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(AgentRunCancellationResponse.from(result));
        };
    }

    @GetMapping("/api/agent/traces/{traceId}")
    public ResponseEntity<AgentTraceView> trace(@PathVariable String traceId, Authentication authentication) {
        ActorIdentity owner = actorResolver.resolve(authentication);
        AgentTrace trace = traceRepository.findByTraceIdAndOwner(traceId, owner.key());
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(AgentTraceView.from(trace));
    }

    @GetMapping("/api/agent/traces")
    public List<AgentTraceView> recentTraces(
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        ActorIdentity owner = actorResolver.resolve(authentication);
        return traceRepository.findRecentByOwner(owner.key(), safeLimit).stream()
                .map(AgentTraceView::from)
                .toList();
    }

    @GetMapping("/api/agent/tool-confirmations")
    public List<ToolConfirmation> pendingConfirmations(
            @RequestParam(defaultValue = "default") String sessionId,
            Authentication authentication
    ) {
        ActorIdentity owner = actorResolver.resolve(authentication);
        return confirmationService.findPendingBySessionId(owner, sessionId);
    }

    @PostMapping("/api/agent/tool-confirmations/{confirmationId}/approve")
    public ToolConfirmation approveConfirmation(@PathVariable String confirmationId) {
        return approvalOrchestrator == null
                ? confirmationService.approve(confirmationId)
                : approvalOrchestrator.approve(confirmationId);
    }

    @PostMapping("/api/agent/tool-confirmations/{confirmationId}/reject")
    public ToolConfirmation rejectConfirmation(
            @PathVariable String confirmationId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body == null ? null : body.get("reason");
        return approvalOrchestrator == null
                ? confirmationService.reject(confirmationId, reason)
                : approvalOrchestrator.reject(confirmationId, reason);
    }

    @ExceptionHandler(AgentApprovalConflictException.class)
    public ResponseEntity<Map<String, String>> approvalConflict(
            AgentApprovalConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }

    @Autowired(required = false)
    void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setTraceRepository(AgentTraceRepository traceRepository) {
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

    @Autowired(required = false)
    void setActorResolver(RequestActorResolver actorResolver) {
        this.actorResolver = actorResolver;
    }

    @Autowired(required = false)
    void setRuntimeService(AgentRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Autowired(required = false)
    void setRuntimeExecutor(AgentRuntimeExecutor runtimeExecutor) {
        this.runtimeExecutor = runtimeExecutor;
    }

    @Autowired(required = false)
    void setRunCoordinator(AgentRunCoordinator runCoordinator) {
        this.runCoordinator = runCoordinator;
    }

    @Autowired(required = false)
    void setApprovalOrchestrator(AgentApprovalOrchestrator approvalOrchestrator) {
        this.approvalOrchestrator = approvalOrchestrator;
    }

    @Autowired(required = false)
    void setCancellationService(AgentCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    private ServerSentEvent<String> sse(AgentRunEvent event) {
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("runId", event.runId());
        envelope.put("sequence", event.sequence());
        envelope.put("type", event.type().name());
        envelope.put("payload", readPayload(event.payloadJson()));
        return ServerSentEvent.<String>builder()
                .id(Long.toString(event.sequence()))
                .event(eventName(event.type()))
                .data(writeJson(envelope))
                .build();
    }

    private JsonNode readPayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode().put("raw", payloadJson);
        }
    }

    private String eventName(AgentRunEventType type) {
        return switch (type) {
            case RUN_CREATED -> "created";
            case RUN_STARTED -> "started";
            case TOKEN_DELTA -> "token";
            case RUN_COMPLETED -> "final";
            case RUN_FAILED -> "error";
            case RUN_CANCELLED -> "cancelled";
            default -> type.name().toLowerCase().replace('_', '-');
        };
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"failed to serialize console event\"}";
        }
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }

    private String ownedConversationId(ActorIdentity owner, String sessionId) {
        return new OwnedConversationId(owner, sessionId).encode();
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

    private record AgentRunView(
            String runId,
            String sessionId,
            String traceId,
            String status,
            int currentStep,
            int maxSteps,
            long version,
            long lastEventSequence,
            String pendingApprovalId,
            String finalAnswer,
            String errorMessage,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant completedAt
    ) {

        private static AgentRunView from(AgentRun run) {
            return new AgentRunView(
                    run.runId(), run.sessionId(), run.traceId(), run.status().name(),
                    run.currentStep(), run.maxSteps(), run.version(), run.lastEventSequence(),
                    run.pendingApprovalId(), run.finalAnswer(), run.errorMessage(),
                    run.createdAt(), run.updatedAt(), run.completedAt()
            );
        }
    }

    private record AgentRunSubmissionResponse(
            String runId,
            String traceId,
            String status,
            String pendingApprovalId,
            String errorMessage
    ) {
        private static AgentRunSubmissionResponse from(AgentRun run) {
            return new AgentRunSubmissionResponse(
                    run.runId(), run.traceId(), run.status().name(),
                    run.pendingApprovalId(), run.errorMessage());
        }
    }

    private record AgentRunCancellationResponse(
            String runId,
            String status,
            boolean newlyCancelled
    ) {
        private static AgentRunCancellationResponse from(AgentRunCancellation cancellation) {
            AgentRun run = cancellation.run();
            return new AgentRunCancellationResponse(
                    run == null ? null : run.runId(),
                    run == null ? null : run.status().name(),
                    cancellation.newlyCancelled());
        }
    }
}
