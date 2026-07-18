package com.hkdzagent.agent.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AgentTrace {

    private final String traceId;
    private final String sessionId;
    private final String userMessage;
    private final Instant startedAt;
    private final List<AgentTraceEvent> events = new ArrayList<>();
    private TraceStatus status = TraceStatus.RUNNING;
    private Instant endedAt;

    AgentTrace(String traceId, String sessionId, String userMessage) {
        this(traceId, sessionId, userMessage, Instant.now(), TraceStatus.RUNNING, null, List.of());
    }

    AgentTrace(
            String traceId,
            String sessionId,
            String userMessage,
            Instant startedAt,
            TraceStatus status,
            Instant endedAt,
            List<AgentTraceEvent> events
    ) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.status = status == null ? TraceStatus.RUNNING : status;
        this.endedAt = endedAt;
        if (events != null) {
            this.events.addAll(events);
        }
    }

    public String traceId() {
        return traceId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String userMessage() {
        return userMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public synchronized Instant endedAt() {
        return endedAt;
    }

    public synchronized TraceStatus status() {
        return status;
    }

    public synchronized Long durationMs() {
        Instant finishedAt = endedAt == null ? Instant.now() : endedAt;
        return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    public synchronized List<AgentTraceEvent> events() {
        return List.copyOf(events);
    }

    synchronized void addEvent(AgentTraceEvent event) {
        events.add(event);
    }

    synchronized void finish(TraceStatus status) {
        this.status = status;
        this.endedAt = Instant.now();
    }

    @Override
    public synchronized String toString() {
        return "AgentTrace{" +
                "traceId='" + traceId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", userMessage='" + userMessage + '\'' +
                ", status=" + status +
                ", events=" + events +
                '}';
    }
}
