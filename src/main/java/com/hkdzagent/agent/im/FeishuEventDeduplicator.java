package com.hkdzagent.agent.im;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FeishuEventDeduplicator {

    private final Set<String> processedEventKeys = ConcurrentHashMap.newKeySet();

    public boolean firstSeen(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return true;
        }
        return processedEventKeys.add(eventKey);
    }
}
