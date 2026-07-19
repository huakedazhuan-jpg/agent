package com.hkdzagent.agent.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryAgentTraceRepository implements AgentTraceRepository {

    private final Map<String, AgentTrace> traces = new LinkedHashMap<>();

    @Override
    public synchronized AgentTrace save(AgentTrace trace) {
        traces.put(trace.traceId(), trace);
        return trace;
    }

    @Override
    public synchronized AgentTrace findByTraceId(String traceId) {
        return traces.get(traceId);
    }

    @Override
    public synchronized AgentTrace findByTraceIdAndOwner(String traceId, String ownerKey) {
        AgentTrace trace = traces.get(traceId);
        return trace != null && trace.ownerKey().equals(ownerKey) ? trace : null;
    }

    @Override
    public synchronized List<AgentTrace> findRecent(int limit) {
        return recent(traces.values().stream().toList(), limit);
    }

    @Override
    public synchronized List<AgentTrace> findRecentByOwner(String ownerKey, int limit) {
        return recent(traces.values().stream()
                .filter(trace -> trace.ownerKey().equals(ownerKey))
                .toList(), limit);
    }

    private List<AgentTrace> recent(List<AgentTrace> source, int limit) {
        List<AgentTrace> recent = new ArrayList<>(source);
        List<AgentTrace> reversed = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0 && reversed.size() < limit; i--) {
            reversed.add(recent.get(i));
        }
        return List.copyOf(reversed);
    }

    @Override
    public synchronized void addEvent(String traceId, AgentTraceEvent event) {
        AgentTraceRepository.super.addEvent(traceId, event);
    }

    @Override
    public synchronized void finish(String traceId, TraceStatus status) {
        AgentTraceRepository.super.finish(traceId, status);
    }
}
