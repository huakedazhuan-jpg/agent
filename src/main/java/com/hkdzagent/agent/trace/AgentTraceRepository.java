package com.hkdzagent.agent.trace;

import java.util.List;

public interface AgentTraceRepository {

    AgentTrace save(AgentTrace trace);

    AgentTrace findByTraceId(String traceId);

    List<AgentTrace> findRecent(int limit);

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
