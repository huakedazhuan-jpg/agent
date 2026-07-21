package com.hkdzagent.agent.im;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class FeishuResultOutboxScheduler {

    private final FeishuResultOutboxRepository repository;
    private final FeishuResultOutboxProcessor processor;
    private final FeishuProperties.Outbox properties;
    private final Clock clock;

    @Autowired
    public FeishuResultOutboxScheduler(
            FeishuResultOutboxRepository repository,
            FeishuResultOutboxProcessor processor,
            FeishuProperties properties
    ) {
        this(repository, processor, properties, Clock.systemUTC());
    }

    FeishuResultOutboxScheduler(
            FeishuResultOutboxRepository repository,
            FeishuResultOutboxProcessor processor,
            FeishuProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties.outbox();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${feishu.outbox.poll-interval:5s}")
    public void deliverReadyMessages() {
        repository.deadLetterExhaustedStale(
                clock.instant(), properties.processingTimeout(), properties.maxAttempts());
        repository.findReadyIds(
                        clock.instant(), properties.processingTimeout(),
                        properties.maxAttempts(), properties.pollBatchSize())
                .forEach(processor::process);
    }
}
