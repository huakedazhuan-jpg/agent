package com.hkdzagent.agent.im;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Component
public class FeishuResultOutboxMetrics implements MeterBinder {

    private static final String PREFIX = "xingclaw.feishu.outbox";

    private final FeishuResultOutboxRepository repository;
    private final FeishuProperties.Outbox properties;
    private final Clock clock;

    @Autowired
    public FeishuResultOutboxMetrics(
            FeishuResultOutboxRepository repository,
            FeishuProperties properties
    ) {
        this(repository, properties, Clock.systemUTC());
    }

    FeishuResultOutboxMetrics(
            FeishuResultOutboxRepository repository,
            FeishuProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties.outbox();
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (FeishuResultOutboxMessage.Status status
                : FeishuResultOutboxMessage.Status.values()) {
            Gauge.builder(PREFIX + ".messages", repository,
                            candidate -> candidate.countByStatus(status))
                    .description("Number of Feishu result outbox messages by state")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder(PREFIX + ".ready", this, FeishuResultOutboxMetrics::readyCount)
                .description("Feishu result outbox messages ready to be claimed")
                .register(registry);
        Gauge.builder(PREFIX + ".oldest.outstanding.age.seconds", this,
                        FeishuResultOutboxMetrics::oldestOutstandingAgeSeconds)
                .description("Age in seconds of the oldest outstanding Feishu result")
                .baseUnit("seconds")
                .register(registry);
    }

    private double readyCount() {
        return repository.countReady(
                clock.instant(), properties.processingTimeout(), properties.maxAttempts());
    }

    private double oldestOutstandingAgeSeconds() {
        Instant oldest = repository.findOldestOutstandingCreatedAt();
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
    }
}
