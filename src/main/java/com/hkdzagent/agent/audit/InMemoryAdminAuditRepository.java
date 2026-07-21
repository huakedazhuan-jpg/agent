package com.hkdzagent.agent.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryAdminAuditRepository implements AdminAuditRepository {

    private final Map<String, AdminAuditEvent> events = new LinkedHashMap<>();

    @Override
    public synchronized AdminAuditEvent save(AdminAuditEvent event) {
        if (events.putIfAbsent(event.id(), event) != null) {
            throw new IllegalStateException("admin audit event already exists: " + event.id());
        }
        return event;
    }

    @Override
    public synchronized List<AdminAuditEvent> findRecent(int limit) {
        return new ArrayList<>(events.values()).stream()
                .sorted(Comparator.comparing(AdminAuditEvent::createdAt)
                        .reversed()
                        .thenComparing(AdminAuditEvent::id))
                .limit(Math.max(1, limit))
                .toList();
    }
}
