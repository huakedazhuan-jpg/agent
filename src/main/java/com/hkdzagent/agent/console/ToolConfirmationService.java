package com.hkdzagent.agent.console;

import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ToolConfirmationService {

    private final Map<String, ToolConfirmation> confirmations = new LinkedHashMap<>();
    private final AgentTraceSanitizer sanitizer;

    public ToolConfirmationService() {
        this(new AgentTraceSanitizer(120));
    }

    public ToolConfirmationService(AgentTraceSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public synchronized ToolConfirmation requestConfirmation(
            String sessionId,
            String traceId,
            String toolName,
            String arguments
    ) {
        ToolConfirmation confirmation = new ToolConfirmation(
                UUID.randomUUID().toString(),
                normalize(sessionId),
                traceId,
                toolName,
                sanitizer.preview(arguments),
                ToolConfirmation.Status.PENDING,
                null,
                Instant.now(),
                null
        );
        confirmations.put(confirmation.id(), confirmation);
        return confirmation;
    }

    public synchronized List<ToolConfirmation> findPendingBySessionId(String sessionId) {
        String normalizedSessionId = normalize(sessionId);
        List<ToolConfirmation> pending = new ArrayList<>();
        for (ToolConfirmation confirmation : confirmations.values()) {
            if (confirmation.status() == ToolConfirmation.Status.PENDING
                    && confirmation.sessionId().equals(normalizedSessionId)) {
                pending.add(confirmation);
            }
        }
        return List.copyOf(pending);
    }

    public synchronized ToolConfirmation findById(String confirmationId) {
        return confirmations.get(confirmationId);
    }

    public synchronized ToolConfirmation approve(String confirmationId) {
        ToolConfirmation confirmation = requireConfirmation(confirmationId).approve();
        confirmations.put(confirmation.id(), confirmation);
        return confirmation;
    }

    public synchronized ToolConfirmation reject(String confirmationId, String reason) {
        ToolConfirmation confirmation = requireConfirmation(confirmationId).reject(reason);
        confirmations.put(confirmation.id(), confirmation);
        return confirmation;
    }

    private ToolConfirmation requireConfirmation(String confirmationId) {
        ToolConfirmation confirmation = confirmations.get(confirmationId);
        if (confirmation == null) {
            throw new IllegalArgumentException("confirmation not found: " + confirmationId);
        }
        return confirmation;
    }

    private String normalize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }
}
