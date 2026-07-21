package com.hkdzagent.agent.im;

import com.hkdzagent.agent.audit.AdminAuditEvent;
import com.hkdzagent.agent.audit.AdminAuditRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class FeishuResultOutboxAdminService {

    private final FeishuResultOutboxRepository repository;
    private final AdminAuditRepository auditRepository;
    private final FeishuProperties.Outbox properties;
    private final Clock clock;

    public FeishuResultOutboxAdminService(
            FeishuResultOutboxRepository repository,
            AdminAuditRepository auditRepository,
            FeishuProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.properties = properties.outbox();
        this.clock = clock;
    }

    public Summary summary() {
        Instant now = clock.instant();
        long pending = repository.countByStatus(FeishuResultOutboxMessage.Status.PENDING);
        long processing = repository.countByStatus(FeishuResultOutboxMessage.Status.PROCESSING);
        long retryable = repository.countByStatus(FeishuResultOutboxMessage.Status.RETRYABLE);
        long sent = repository.countByStatus(FeishuResultOutboxMessage.Status.SENT);
        long dead = repository.countByStatus(FeishuResultOutboxMessage.Status.DEAD);
        Instant oldest = repository.findOldestOutstandingCreatedAt();
        return new Summary(
                pending + processing + retryable + sent + dead,
                pending, processing, retryable, sent, dead,
                repository.countReady(
                        now, properties.processingTimeout(), properties.maxAttempts()),
                oldest,
                oldest == null ? null : Duration.between(oldest, now));
    }

    public List<MessageView> findByStatus(String status, int limit) {
        FeishuResultOutboxMessage.Status parsed = parseStatus(status);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.findByStatus(parsed, safeLimit).stream()
                .map(MessageView::from)
                .toList();
    }

    @Transactional
    public MessageView retryDead(String id, ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("admin actor is required");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("outbox message id is required");
        }
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("outbox message id must be a UUID", exception);
        }
        Instant now = clock.instant();
        FeishuResultOutboxMessage retried = repository.retryDead(id, now);
        if (retried != null) {
            auditRepository.save(new AdminAuditEvent(
                    UUID.randomUUID().toString(), actor.key(),
                    "FEISHU_OUTBOX_RETRY", "FEISHU_RESULT_OUTBOX", id,
                    AdminAuditEvent.Outcome.SUCCEEDED,
                    "manual retry; delivery attempts reset", now));
            return MessageView.from(retried);
        }
        FeishuResultOutboxMessage current = repository.findById(id);
        if (current == null) {
            throw new OutboxMessageNotFoundException(id);
        }
        throw new OutboxMessageStateException(id, current.status());
    }

    private FeishuResultOutboxMessage.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("outbox status is required");
        }
        try {
            return FeishuResultOutboxMessage.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported outbox status: " + status, exception);
        }
    }

    public record Summary(
            long total,
            long pending,
            long processing,
            long retryable,
            long sent,
            long dead,
            long ready,
            Instant oldestOutstandingAt,
            Duration oldestOutstandingAge
    ) {
    }

    public record MessageView(
            String id,
            String runId,
            String recipient,
            FeishuResultOutboxMessage.Type type,
            FeishuResultOutboxMessage.Status status,
            Instant createdAt,
            Instant claimedAt,
            Instant sentAt,
            Instant deadAt,
            Instant nextAttemptAt,
            int attemptCount,
            String lastError
    ) {
        private static MessageView from(FeishuResultOutboxMessage message) {
            return new MessageView(
                    message.id(), message.runId(), mask(message.openId()),
                    message.type(), message.status(),
                    message.createdAt(), message.claimedAt(), message.sentAt(), message.deadAt(),
                    message.nextAttemptAt(), message.attemptCount(), message.lastError());
        }

        private static String mask(String value) {
            if (value == null || value.isBlank()) {
                return "***";
            }
            int visible = Math.min(4, value.length());
            return "***" + value.substring(value.length() - visible);
        }
    }

    public static final class OutboxMessageNotFoundException extends RuntimeException {
        public OutboxMessageNotFoundException(String id) {
            super("outbox message not found: " + id);
        }
    }

    public static final class OutboxMessageStateException extends RuntimeException {
        public OutboxMessageStateException(
                String id, FeishuResultOutboxMessage.Status status
        ) {
            super("outbox message " + id + " cannot be retried from status " + status);
        }
    }
}
