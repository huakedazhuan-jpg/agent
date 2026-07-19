package com.hkdzagent.agent.console;

import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.security.ActorIdentity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ToolConfirmationService {

    private final ToolConfirmationRepository repository;
    private final AgentTraceSanitizer sanitizer;
    private final Duration ttl;
    private final Clock clock;

    public ToolConfirmationService() {
        this(
                new InMemoryToolConfirmationRepository(),
                new AgentTraceSanitizer(120),
                Duration.ofMinutes(15),
                Clock.systemUTC()
        );
    }

    public ToolConfirmationService(AgentTraceSanitizer sanitizer) {
        this(
                new InMemoryToolConfirmationRepository(),
                sanitizer,
                Duration.ofMinutes(15),
                Clock.systemUTC()
        );
    }

    public ToolConfirmationService(
            ToolConfirmationRepository repository,
            AgentTraceSanitizer sanitizer,
            Duration ttl,
            Clock clock
    ) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("tool approval ttl must be positive");
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    public ToolConfirmation requestConfirmation(
            String sessionId,
            String traceId,
            String toolName,
            String arguments
    ) {
        return requestConfirmation(ActorIdentity.localAnonymous(), sessionId, traceId, toolName, arguments);
    }

    public ToolConfirmation requestConfirmation(
            ActorIdentity owner,
            String sessionId,
            String traceId,
            String toolName,
            String arguments
    ) {
        return requestConfirmation(owner, sessionId, traceId, null, toolName, arguments);
    }

    public ToolConfirmation requestConfirmationForRun(
            ActorIdentity owner,
            String sessionId,
            String traceId,
            String runId,
            String toolName,
            String arguments
    ) {
        return requestConfirmation(owner, sessionId, traceId, runId, toolName, arguments);
    }

    private ToolConfirmation requestConfirmation(
            ActorIdentity owner,
            String sessionId,
            String traceId,
            String runId,
            String toolName,
            String arguments
    ) {
        Instant createdAt = clock.instant();
        return repository.save(new ToolConfirmation(
                UUID.randomUUID().toString(),
                owner.key(),
                normalize(sessionId),
                traceId,
                runId,
                toolName,
                sanitizer.preview(arguments),
                ToolConfirmation.Status.PENDING,
                null,
                createdAt,
                createdAt.plus(ttl),
                null
        ));
    }

    public List<ToolConfirmation> findPendingBySessionId(ActorIdentity owner, String sessionId) {
        repository.expirePendingBefore(clock.instant());
        return repository.findPendingByOwnerAndSessionId(owner.key(), normalize(sessionId));
    }

    public ToolConfirmation findById(String confirmationId) {
        repository.expirePendingBefore(clock.instant());
        return repository.findById(confirmationId);
    }

    public int expirePending() {
        return repository.expirePendingBefore(clock.instant());
    }

    public ToolConfirmation approve(String confirmationId) {
        return decide(confirmationId, ToolConfirmation.Status.APPROVED, "approved");
    }

    public ToolConfirmation reject(String confirmationId, String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "rejected" : reason;
        return decide(confirmationId, ToolConfirmation.Status.REJECTED, normalizedReason);
    }

    private ToolConfirmation decide(
            String confirmationId,
            ToolConfirmation.Status status,
            String decisionReason
    ) {
        Instant now = clock.instant();
        repository.expirePendingBefore(now);
        ToolConfirmation decided = repository.decidePending(confirmationId, status, decisionReason, now);
        if (decided != null) {
            return decided;
        }
        ToolConfirmation confirmation = repository.findById(confirmationId);
        if (confirmation == null) {
            throw new IllegalArgumentException("confirmation not found: " + confirmationId);
        }
        throw new IllegalStateException(
                "confirmation is no longer pending: " + confirmationId + " (" + confirmation.status() + ")"
        );
    }

    private String normalize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }
}
