package com.hkdzagent.agent.im;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFeishuResultOutboxRepositoryTest {

    private JdbcFeishuResultOutboxRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:feishu_outbox_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE feishu_result_outbox (
                    id UUID PRIMARY KEY,
                    run_id UUID NOT NULL,
                    open_id VARCHAR(256) NOT NULL,
                    message_text CLOB NOT NULL,
                    notification_type VARCHAR(32) NOT NULL,
                    deduplication_key VARCHAR(512) NOT NULL UNIQUE,
                    status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    claimed_at TIMESTAMP,
                    sent_at TIMESTAMP,
                    dead_at TIMESTAMP,
                    next_attempt_at TIMESTAMP,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_error VARCHAR(500)
                )
                """);
        repository = new JdbcFeishuResultOutboxRepository(
                new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    @Test
    void deduplicatesByBusinessKeyAndAllowsMultipleNotificationsPerRun() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String runId = UUID.randomUUID().toString();
        FeishuResultOutboxMessage first = message(runId, now);
        FeishuResultOutboxMessage duplicate = new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "ou_test", "duplicate", FeishuResultOutboxMessage.Type.FINAL_RESULT,
                first.deduplicationKey(), FeishuResultOutboxMessage.Status.PENDING,
                now.plusSeconds(1), null, null, null, now.plusSeconds(1), 0, null);

        assertThat(repository.enqueue(first)).isTrue();
        assertThat(repository.enqueue(duplicate)).isFalse();
        FeishuResultOutboxMessage approval = new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), runId, "ou_test", "approval needed",
                FeishuResultOutboxMessage.Type.APPROVAL_REQUIRED,
                "run:" + runId + ":approval:approval-1",
                FeishuResultOutboxMessage.Status.PENDING,
                now.plusSeconds(2), null, null, null, now.plusSeconds(2), 0, null);
        assertThat(repository.enqueue(approval)).isTrue();
        assertThat(repository.findByDeduplicationKey(first.deduplicationKey()))
                .isEqualTo(first);
        assertThat(repository.findByDeduplicationKey(approval.deduplicationKey()))
                .isEqualTo(approval);
    }

    @Test
    void claimsRetriesAndMarksSent() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FeishuResultOutboxMessage pending = message(UUID.randomUUID().toString(), now);
        repository.enqueue(pending);

        FeishuResultOutboxMessage firstAttempt = repository.claim(
                pending.id(), now, Duration.ofMinutes(5), 3);
        assertThat(firstAttempt.status()).isEqualTo(FeishuResultOutboxMessage.Status.PROCESSING);
        assertThat(firstAttempt.attemptCount()).isOne();
        assertThat(repository.claim(
                pending.id(), now.plusSeconds(1), Duration.ofMinutes(5), 3)).isNull();

        repository.markFailed(
                pending.id(), "temporary failure", now.plusSeconds(2),
                now.plusSeconds(30), false);
        assertThat(repository.findReadyIds(
                now.plusSeconds(29), Duration.ofMinutes(5), 3, 10)).isEmpty();
        assertThat(repository.findReadyIds(
                now.plusSeconds(30), Duration.ofMinutes(5), 3, 10))
                .containsExactly(pending.id());

        FeishuResultOutboxMessage secondAttempt = repository.claim(
                pending.id(), now.plusSeconds(30), Duration.ofMinutes(5), 3);
        assertThat(secondAttempt.attemptCount()).isEqualTo(2);
        repository.markSent(pending.id(), now.plusSeconds(31));

        FeishuResultOutboxMessage sent = repository.findById(pending.id());
        assertThat(sent.status()).isEqualTo(FeishuResultOutboxMessage.Status.SENT);
        assertThat(sent.sentAt()).isEqualTo(now.plusSeconds(31));
        assertThat(sent.lastError()).isNull();
        assertThat(repository.findReadyIds(
                now.plusSeconds(60), Duration.ofMinutes(5), 3, 10)).isEmpty();
    }

    @Test
    void recoversStaleClaimAndStopsAfterTerminalFailure() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FeishuResultOutboxMessage pending = message(UUID.randomUUID().toString(), now);
        repository.enqueue(pending);
        repository.claim(pending.id(), now, Duration.ofMinutes(5), 2);

        assertThat(repository.findReadyIds(
                now.plusSeconds(299), Duration.ofMinutes(5), 2, 10)).isEmpty();
        assertThat(repository.findReadyIds(
                now.plusSeconds(300), Duration.ofMinutes(5), 2, 10))
                .containsExactly(pending.id());

        repository.claim(pending.id(), now.plusSeconds(300), Duration.ofMinutes(5), 2);
        repository.markFailed(
                pending.id(), "permanent failure", now.plusSeconds(301),
                now.plusSeconds(330), true);

        FeishuResultOutboxMessage dead = repository.findById(pending.id());
        assertThat(dead.status()).isEqualTo(FeishuResultOutboxMessage.Status.DEAD);
        assertThat(dead.deadAt()).isEqualTo(now.plusSeconds(301));
        assertThat(repository.findReadyIds(
                now.plusSeconds(600), Duration.ofMinutes(5), 2, 10)).isEmpty();
    }

    @Test
    void deadLettersAStaleClaimAfterTheFinalAttemptCrashes() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FeishuResultOutboxMessage pending = message(UUID.randomUUID().toString(), now);
        repository.enqueue(pending);
        repository.claim(pending.id(), now, Duration.ofMinutes(5), 1);

        int deadLettered = repository.deadLetterExhaustedStale(
                now.plusSeconds(300), Duration.ofMinutes(5), 1);

        FeishuResultOutboxMessage dead = repository.findById(pending.id());
        assertThat(deadLettered).isOne();
        assertThat(dead.status()).isEqualTo(FeishuResultOutboxMessage.Status.DEAD);
        assertThat(dead.deadAt()).isEqualTo(now.plusSeconds(300));
        assertThat(dead.lastError()).contains("final attempt");
    }

    @Test
    void calculatesOperationalCountsAndListsByStatus() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FeishuResultOutboxMessage pending = message(UUID.randomUUID().toString(), now);
        FeishuResultOutboxMessage dead = message(
                UUID.randomUUID().toString(), now.plusSeconds(1));
        repository.enqueue(pending);
        repository.enqueue(dead);
        repository.claim(dead.id(), now.plusSeconds(1), Duration.ofMinutes(5), 1);
        repository.markFailed(
                dead.id(), "permanent failure", now.plusSeconds(2),
                now.plusSeconds(30), true);

        assertThat(repository.countByStatus(FeishuResultOutboxMessage.Status.PENDING)).isOne();
        assertThat(repository.countByStatus(FeishuResultOutboxMessage.Status.DEAD)).isOne();
        assertThat(repository.countReady(now, Duration.ofMinutes(5), 3)).isOne();
        assertThat(repository.findOldestOutstandingCreatedAt()).isEqualTo(now);
        assertThat(repository.findByStatus(FeishuResultOutboxMessage.Status.DEAD, 10))
                .extracting(FeishuResultOutboxMessage::id)
                .containsExactly(dead.id());
    }

    @Test
    void retriesOnlyDeadMessageAndResetsDeliveryAttempts() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        FeishuResultOutboxMessage message = message(UUID.randomUUID().toString(), now);
        repository.enqueue(message);
        repository.claim(message.id(), now, Duration.ofMinutes(5), 1);
        repository.markFailed(
                message.id(), "last delivery error", now.plusSeconds(1),
                now.plusSeconds(30), true);

        FeishuResultOutboxMessage retried = repository.retryDead(
                message.id(), now.plusSeconds(2));

        assertThat(retried.status()).isEqualTo(FeishuResultOutboxMessage.Status.RETRYABLE);
        assertThat(retried.attemptCount()).isZero();
        assertThat(retried.nextAttemptAt()).isEqualTo(now.plusSeconds(2));
        assertThat(retried.deadAt()).isNull();
        assertThat(retried.lastError()).isEqualTo("last delivery error");
        assertThat(repository.retryDead(message.id(), now.plusSeconds(3))).isNull();
    }

    private FeishuResultOutboxMessage message(String runId, Instant createdAt) {
        return new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), runId, "ou_test", "final answer",
                FeishuResultOutboxMessage.Type.FINAL_RESULT,
                "run:" + runId + ":final-result",
                FeishuResultOutboxMessage.Status.PENDING,
                createdAt, null, null, null, createdAt, 0, null);
    }
}
