package com.hkdzagent.agent.rag;

import java.util.List;

public record RagAnswer(String answer, List<String> sources) {
    public RagAnswer {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
