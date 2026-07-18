package com.hkdzagent.agent.rag;

public record KnowledgeSearchResult(
        String documentId,
        String sourceName,
        int chunkIndex,
        String sourceRef,
        String content,
        double score
) {
}
