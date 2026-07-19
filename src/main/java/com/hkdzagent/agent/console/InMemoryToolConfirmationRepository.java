package com.hkdzagent.agent.console;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryToolConfirmationRepository implements ToolConfirmationRepository {

    private final Map<String, ToolConfirmation> confirmations = new LinkedHashMap<>();

    @Override
    public synchronized ToolConfirmation save(ToolConfirmation confirmation) {
        confirmations.put(confirmation.id(), confirmation);
        return confirmation;
    }

    @Override
    public synchronized List<ToolConfirmation> findPendingByOwnerAndSessionId(String ownerKey, String sessionId) {
        List<ToolConfirmation> pending = new ArrayList<>();
        for (ToolConfirmation confirmation : confirmations.values()) {
            if (confirmation.status() == ToolConfirmation.Status.PENDING
                    && confirmation.ownerKey().equals(ownerKey)
                    && confirmation.sessionId().equals(sessionId)) {
                pending.add(confirmation);
            }
        }
        return List.copyOf(pending);
    }

    @Override
    public synchronized ToolConfirmation findById(String confirmationId) {
        return confirmations.get(confirmationId);
    }

    @Override
    public synchronized ToolConfirmation decidePending(
            String confirmationId,
            ToolConfirmation.Status status,
            String decisionReason,
            Instant decidedAt
    ) {
        ToolConfirmation current = confirmations.get(confirmationId);
        if (current == null || current.status() != ToolConfirmation.Status.PENDING) {
            return null;
        }
        ToolConfirmation decided = new ToolConfirmation(
                current.id(),
                current.ownerKey(),
                current.sessionId(),
                current.traceId(),
                current.toolName(),
                current.argumentsPreview(),
                status,
                decisionReason,
                current.createdAt(),
                current.expiresAt(),
                decidedAt
        );
        confirmations.put(confirmationId, decided);
        return decided;
    }

    @Override
    public synchronized int expirePendingBefore(Instant cutoff) {
        int expired = 0;
        for (ToolConfirmation confirmation : List.copyOf(confirmations.values())) {
            if (confirmation.status() == ToolConfirmation.Status.PENDING
                    && !confirmation.expiresAt().isAfter(cutoff)) {
                decidePending(
                        confirmation.id(),
                        ToolConfirmation.Status.EXPIRED,
                        "approval expired",
                        cutoff
                );
                expired++;
            }
        }
        return expired;
    }
}
