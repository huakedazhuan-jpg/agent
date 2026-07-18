package com.hkdzagent.agent.rag;

import java.util.List;

public class KnowledgeSearchTool {

    private static final int DEFAULT_TOP_K = 4;

    private final LocalKnowledgeBase knowledgeBase;

    public KnowledgeSearchTool(LocalKnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public String execute(String query) {
        List<KnowledgeSearchResult> results = knowledgeBase.search(query, DEFAULT_TOP_K);
        if (results.isEmpty()) {
            return "No relevant knowledge base content found.\nSources:";
        }

        StringBuilder output = new StringBuilder();
        for (KnowledgeSearchResult result : results) {
            if (!output.isEmpty()) {
                output.append("\n\n");
            }
            output.append(result.content());
        }

        output.append("\n\nSources:");
        results.stream()
                .map(KnowledgeSearchResult::sourceRef)
                .distinct()
                .forEach(source -> output.append("\n- ").append(source));
        return output.toString();
    }
}
