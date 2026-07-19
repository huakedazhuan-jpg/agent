package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFeishuEventInboxRepositoryTest {

    private JdbcFeishuEventInboxRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:feishu_inbox_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE feishu_event_inbox (
                    event_id VARCHAR(256) PRIMARY KEY,
                    event_type VARCHAR(128) NOT NULL,
                    open_id VARCHAR(256),
                    payload JSON NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    received_at TIMESTAMP NOT NULL,
                    claimed_at TIMESTAMP,
                    processed_at TIMESTAMP,
                    next_attempt_at TIMESTAMP,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    last_error CLOB
                )
                """);
        repository = new JdbcFeishuEventInboxRepository(
                new NamedParameterJdbcTemplate(jdbcTemplate),
                new ObjectMapper()
        );
    }

    @Test
    void persistsEventAndDeduplicatesByEventId() {
        FeishuInboxEvent event = event("event-1", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(repository.receive(event)).isTrue();
        assertThat(repository.receive(event)).isFalse();

        FeishuInboxEvent stored = repository.findById("event-1");
        assertThat(stored.eventType()).isEqualTo("im.message.receive_v1");
        assertThat(stored.openId()).isEqualTo("open-1");
        assertThat(stored.payload()).contains("event-1");
        assertThat(stored.status()).isEqualTo(FeishuInboxEvent.Status.RECEIVED);
    }

    @Test
    void atomicallyClaimsAndCompletesEventOnce() {
        Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.receive(event("event-claim", receivedAt));

        FeishuInboxEvent claimed = repository.claim(
                "event-claim",
                receivedAt.plusSeconds(1),
                Duration.ofMinutes(5),
                3
        );
        FeishuInboxEvent duplicateClaim = repository.claim(
                "event-claim",
                receivedAt.plusSeconds(2),
                Duration.ofMinutes(5),
                3
        );

        assertThat(claimed.status()).isEqualTo(FeishuInboxEvent.Status.PROCESSING);
        assertThat(claimed.retryCount()).isOne();
        assertThat(duplicateClaim).isNull();

        repository.markProcessed("event-claim", receivedAt.plusSeconds(3));
        assertThat(repository.findById("event-claim").status())
                .isEqualTo(FeishuInboxEvent.Status.PROCESSED);
        assertThat(repository.findReadyEventIds(
                receivedAt.plusSeconds(4), Duration.ofMinutes(5), 3, 10)).isEmpty();
    }

    @Test
    void retriesFailedAndStaleProcessingEventsUntilAttemptLimit() {
        Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.receive(event("event-retry", receivedAt));
        repository.claim("event-retry", receivedAt, Duration.ofMinutes(5), 2);
        repository.markFailed(
                "event-retry",
                "temporary model failure",
                receivedAt.plusSeconds(1),
                receivedAt.plusSeconds(30),
                false
        );

        assertThat(repository.findReadyEventIds(
                receivedAt.plusSeconds(29), Duration.ofMinutes(5), 2, 10)).isEmpty();
        assertThat(repository.findReadyEventIds(
                receivedAt.plusSeconds(30), Duration.ofMinutes(5), 2, 10))
                .containsExactly("event-retry");

        FeishuInboxEvent secondAttempt = repository.claim(
                "event-retry",
                receivedAt.plusSeconds(30),
                Duration.ofMinutes(5),
                2
        );
        assertThat(secondAttempt.retryCount()).isEqualTo(2);

        repository.markFailed(
                "event-retry",
                "permanent failure",
                receivedAt.plusSeconds(31),
                receivedAt.plusSeconds(60),
                true
        );
        assertThat(repository.findById("event-retry").status())
                .isEqualTo(FeishuInboxEvent.Status.DEAD);
        assertThat(repository.findReadyEventIds(
                receivedAt.plusSeconds(120), Duration.ofMinutes(5), 2, 10)).isEmpty();
    }

    @Test
    void makesStaleProcessingEventRecoverable() {
        Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.receive(event("event-stale", receivedAt));
        repository.claim("event-stale", receivedAt, Duration.ofMinutes(5), 3);

        assertThat(repository.findReadyEventIds(
                receivedAt.plusSeconds(299), Duration.ofMinutes(5), 3, 10)).isEmpty();
        assertThat(repository.findReadyEventIds(
                receivedAt.plusSeconds(300), Duration.ofMinutes(5), 3, 10))
                .containsExactly("event-stale");
    }

    @Test
    void deadLettersStaleProcessingEventAfterFinalAttempt() {
        Instant receivedAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.receive(event("event-exhausted", receivedAt));
        repository.claim("event-exhausted", receivedAt, Duration.ofMinutes(5), 1);

        int deadLettered = repository.deadLetterExhaustedStale(
                receivedAt.plusSeconds(300),
                Duration.ofMinutes(5),
                1
        );

        FeishuInboxEvent exhausted = repository.findById("event-exhausted");
        assertThat(deadLettered).isOne();
        assertThat(exhausted.status()).isEqualTo(FeishuInboxEvent.Status.DEAD);
        assertThat(exhausted.lastError()).contains("lease expired");
        assertThat(exhausted.processedAt()).isEqualTo(receivedAt.plusSeconds(300));
    }

    private FeishuInboxEvent event(String eventId, Instant receivedAt) {
        return new FeishuInboxEvent(
                eventId,
                "im.message.receive_v1",
                "open-1",
                "{\"header\":{\"event_id\":\"" + eventId + "\"}}",
                FeishuInboxEvent.Status.RECEIVED,
                receivedAt,
                null,
                null,
                receivedAt,
                0,
                null
        );
    }
}
