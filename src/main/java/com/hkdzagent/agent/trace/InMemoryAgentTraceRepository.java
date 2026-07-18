package com.hkdzagent.agent.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryAgentTraceRepository {

    private final Map<String, AgentTrace> traces = new LinkedHashMap<>();

    public synchronized AgentTrace save(AgentTrace trace) {
        traces.put(trace.traceId(), trace);
        return trace;
    }

    public synchronized AgentTrace findByTraceId(String traceId) {
        return traces.get(traceId);
    }

    public synchronized List<AgentTrace> findRecent(int limit) {
        List<AgentTrace> recent = new ArrayList<>(traces.values());
        List<AgentTrace> reversed = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0 && reversed.size() < limit; i--) {
            reversed.add(recent.get(i));
        }
        return List.copyOf(reversed);
    }
}
