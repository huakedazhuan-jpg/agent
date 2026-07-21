package com.hkdzagent.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, authoritative directory of tools exposed by the application.
 */
public final class AgentToolRegistry {

    private final Map<String, AgentTool<?, ?>> toolsByName;
    private final List<String> names;
    private final List<ToolMetadata> metadata;

    public AgentToolRegistry(List<? extends AgentTool<?, ?>> tools) {
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }

        LinkedHashMap<String, AgentTool<?, ?>> registered = new LinkedHashMap<>();
        for (AgentTool<?, ?> tool : tools) {
            if (tool == null) {
                throw new IllegalArgumentException("registered tool must not be null");
            }
            String name = tool.metadata().name();
            if (registered.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("duplicate tool name: " + name);
            }
        }
        this.toolsByName = Map.copyOf(registered);
        this.names = List.copyOf(registered.keySet());
        this.metadata = registered.values().stream()
                .map(AgentTool::metadata)
                .toList();
    }

    public AgentTool<?, ?> require(String name) {
        AgentTool<?, ?> tool = name == null ? null : toolsByName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("tool is not registered: " + name);
        }
        return tool;
    }

    public List<String> names() {
        return names;
    }

    public List<ToolMetadata> metadata() {
        return metadata;
    }
}
