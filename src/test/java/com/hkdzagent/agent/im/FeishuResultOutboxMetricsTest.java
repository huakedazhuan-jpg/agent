package com.hkdzagent.agent.im;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuResultOutboxMetricsTest {

    @Test
    void publishesLowCardinalityBacklogMetricsWithoutMessageData() {
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        InMemoryFeishuResultOutboxRepository repository =
                new InMemoryFeishuResultOutboxRepository();
        FeishuResultOutboxMessage pending = message(now.minus(Duration.ofMinutes(3)));
        FeishuResultOutboxMessage dead = message(now.minus(Duration.ofMinutes(2)));
        repository.enqueue(pending);
        repository.enqueue(dead);
        repository.claim(dead.id(), now.minusSeconds(10), Duration.ofMinutes(5), 1);
        repository.markFailed(dead.id(), "failure", now.minusSeconds(9), now, true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new FeishuResultOutboxMetrics(
                repository, new FeishuProperties(), Clock.fixed(now, ZoneOffset.UTC))
                .bindTo(registry);

        assertThat(registry.get("xingclaw.feishu.outbox.messages")
                .tag("status", "pending").gauge().value()).isEqualTo(1);
        assertThat(registry.get("xingclaw.feishu.outbox.messages")
                .tag("status", "dead").gauge().value()).isEqualTo(1);
        assertThat(registry.get("xingclaw.feishu.outbox.ready").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get("xingclaw.feishu.outbox.oldest.outstanding.age.seconds")
                .gauge().value()).isEqualTo(180);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().equals("open_id")
                                || tag.getKey().equals("run_id")
                                || tag.getKey().equals("error")));
    }

    private FeishuResultOutboxMessage message(Instant createdAt) {
        return new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "open-sensitive", "sensitive final answer",
                FeishuResultOutboxMessage.Type.FINAL_RESULT,
                "notification:" + UUID.randomUUID(),
                FeishuResultOutboxMessage.Status.PENDING,
                createdAt, null, null, null, null, 0, null);
    }
}
