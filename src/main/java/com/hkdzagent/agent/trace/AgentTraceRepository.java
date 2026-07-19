package com.hkdzagent.agent.trace;

import java.util.List;

public interface AgentTraceRepository {

    AgentTrace save(AgentTrace trace);

    AgentTrace findByTraceId(String traceId);

    AgentTrace findByTraceIdAndOwner(String traceId, String ownerKey);

    List<AgentTrace> findRecent(int limit);

    List<AgentTrace> findRecentByOwner(String ownerKey, int limit);

    default void addEvent(String traceId, AgentTraceEvent event) {
        AgentTrace trace = findByTraceId(traceId);
        if (trace != null) {
            trace.addEvent(event);
        }
    }

    default void finish(String traceId, TraceStatus status) {
        AgentTrace trace = findByTraceId(traceId);
        if (trace != null) {
            trace.finish(status);
        }
    }
}
