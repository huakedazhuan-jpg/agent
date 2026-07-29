package com.hkdzagent.agent.memory;

public record MemoryCandidate(
        MemoryType type,
        String content,
        String normalizedKey,
        double importance,
        double confidence
) {
}
