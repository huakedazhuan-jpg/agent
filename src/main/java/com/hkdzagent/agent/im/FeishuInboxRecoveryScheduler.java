package com.hkdzagent.agent.im;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class FeishuInboxRecoveryScheduler {

    private final FeishuEventInboxRepository repository;
    private final FeishuEventProcessor processor;
    private final FeishuProperties.Inbox properties;
    private final Clock clock;

    @Autowired
    public FeishuInboxRecoveryScheduler(
            FeishuEventInboxRepository repository,
            FeishuEventProcessor processor,
            FeishuProperties properties
    ) {
        this(repository, processor, properties, Clock.systemUTC());
    }

    FeishuInboxRecoveryScheduler(
            FeishuEventInboxRepository repository,
            FeishuEventProcessor processor,
            FeishuProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties.inbox();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${feishu.inbox.poll-interval:30s}")
    public void recoverReadyEvents() {
        repository.deadLetterExhaustedStale(
                clock.instant(),
                properties.processingTimeout(),
                properties.maxAttempts()
        );
        repository.findReadyEventIds(
                        clock.instant(),
                        properties.processingTimeout(),
                        properties.maxAttempts(),
                        properties.pollBatchSize()
                )
                .forEach(processor::processAsync);
    }
}
