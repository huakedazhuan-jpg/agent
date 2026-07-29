package com.hkdzagent.agent.context;

import java.util.List;

public interface ContextSource {

    ContextSource EMPTY = request -> List.of();

    List<ContextSection> load(ContextRequest request);
}
