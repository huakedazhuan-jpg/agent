package com.hkdzagent.agent.im;

import com.hkdzagent.agent.audit.AdminAuditEvent;
import com.hkdzagent.agent.audit.InMemoryAdminAuditRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuResultOutboxAdminServiceTest {

    @Test
    void summarizesBacklogAndReturnsMaskedMessageViews() {
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuProperties properties = new FeishuProperties();
        FeishuResultOutboxMessage pending = message(
                "open-sensitive-1234", now.minus(Duration.ofMinutes(10)));
        FeishuResultOutboxMessage dead = message(
                "open-sensitive-5678", now.minus(Duration.ofMinutes(5)));
        repository.enqueue(pending);
        repository.enqueue(dead);
        repository.claim(dead.id(), now.minusSeconds(20), Duration.ofMinutes(5), 1);
        repository.markFailed(
                dead.id(), "sanitized failure", now.minusSeconds(19), now, true);
        FeishuResultOutboxAdminService service = new FeishuResultOutboxAdminService(
                repository, new InMemoryAdminAuditRepository(), properties,
                Clock.fixed(now, ZoneOffset.UTC));

        FeishuResultOutboxAdminService.Summary summary = service.summary();

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.pending()).isOne();
        assertThat(summary.dead()).isOne();
        assertThat(summary.ready()).isOne();
        assertThat(summary.oldestOutstandingAt()).isEqualTo(pending.createdAt());
        assertThat(summary.oldestOutstandingAge()).isEqualTo(Duration.ofMinutes(10));

        FeishuResultOutboxAdminService.MessageView view =
                service.findByStatus("dead", 500).get(0);
        assertThat(view.id()).isEqualTo(dead.id());
        assertThat(view.recipient()).isEqualTo("***5678");
        assertThat(view.recipient()).doesNotContain("open-sensitive");
        assertThat(view.lastError()).isEqualTo("sanitized failure");
    }

    @Test
    void rejectsMissingOrUnknownStatus() {
        FeishuResultOutboxAdminService service = new FeishuResultOutboxAdminService(
                new InMemoryFeishuResultOutboxRepository(),
                new InMemoryAdminAuditRepository(), new FeishuProperties(), Clock.systemUTC());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.findByStatus("", 20));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.findByStatus("unknown", 20));
    }

    @Test
    void retriesDeadMessageOnceAndReportsMissingOrInvalidState() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxMessage dead = message("open-dead", now.minusSeconds(10));
        FeishuResultOutboxMessage pending = message("open-pending", now.minusSeconds(5));
        repository.enqueue(dead);
        repository.enqueue(pending);
        repository.claim(dead.id(), now.minusSeconds(2), Duration.ofMinutes(5), 1);
        repository.markFailed(dead.id(), "failure", now.minusSeconds(1), now, true);
        InMemoryAdminAuditRepository auditRepository = new InMemoryAdminAuditRepository();
        FeishuResultOutboxAdminService service = new FeishuResultOutboxAdminService(
                repository, auditRepository, new FeishuProperties(),
                Clock.fixed(now, ZoneOffset.UTC));
        ActorIdentity admin = ActorIdentity.user("admin-id");

        FeishuResultOutboxAdminService.MessageView retried = service.retryDead(dead.id(), admin);

        assertThat(retried.status()).isEqualTo(FeishuResultOutboxMessage.Status.RETRYABLE);
        assertThat(retried.attemptCount()).isZero();
        assertThat(retried.nextAttemptAt()).isEqualTo(now);
        assertThat(auditRepository.findRecent(10)).singleElement().satisfies(event -> {
            assertThat(event.actorKey()).isEqualTo(admin.key());
            assertThat(event.action()).isEqualTo("FEISHU_OUTBOX_RETRY");
            assertThat(event.resourceId()).isEqualTo(dead.id());
            assertThat(event.outcome()).isEqualTo(AdminAuditEvent.Outcome.SUCCEEDED);
            assertThat(event.createdAt()).isEqualTo(now);
        });
        assertThatThrownBy(() -> service.retryDead(dead.id(), admin))
                .isInstanceOf(FeishuResultOutboxAdminService.OutboxMessageStateException.class);
        assertThatThrownBy(() -> service.retryDead(pending.id(), admin))
                .isInstanceOf(FeishuResultOutboxAdminService.OutboxMessageStateException.class);
        assertThatThrownBy(() -> service.retryDead(UUID.randomUUID().toString(), admin))
                .isInstanceOf(FeishuResultOutboxAdminService.OutboxMessageNotFoundException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.retryDead("not-a-uuid", admin))
                .withMessage("outbox message id must be a UUID");
    }

    private FeishuResultOutboxMessage message(String openId, Instant createdAt) {
        return new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                openId, "sensitive final answer",
                FeishuResultOutboxMessage.Type.FINAL_RESULT,
                "notification:" + UUID.randomUUID(),
                FeishuResultOutboxMessage.Status.PENDING,
                createdAt, null, null, null, null, 0, null);
    }
}
