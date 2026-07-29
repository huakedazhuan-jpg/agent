package com.hkdzagent.agent.context;

import java.util.Objects;

public record ContextSection(
        String id,
        String role,
        String content,
        Kind kind,
        long order,
        double score,
        String pairId
) {
    public enum Kind {
        SYSTEM,
        CURRENT_USER,
        SUMMARY,
        RECENT_MESSAGE,
        LONG_TERM_MEMORY,
        TOOL_OBSERVATION
    }

    public ContextSection {
        id = Objects.requireNonNullElse(id, "");
        role = Objects.requireNonNullElse(role, "user");
        content = Objects.requireNonNullElse(content, "");
        kind = Objects.requireNonNull(kind, "kind");
    }

    public ContextSection withContent(String nextContent) {
        return new ContextSection(id, role, nextContent, kind, order, score, pairId);
    }
}
