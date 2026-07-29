package com.hkdzagent.agent.im;

import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class FeishuResultOutboxProcessor {

    private final FeishuResultOutboxRepository repository;
    private final FeishuReplyClient replyClient;
    private final FeishuProperties.Outbox properties;
    private final Clock clock;
    private final AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(500);

    @Autowired
    public FeishuResultOutboxProcessor(
            FeishuResultOutboxRepository repository,
            FeishuReplyClient replyClient,
            FeishuProperties properties
    ) {
        this(repository, replyClient, properties, Clock.systemUTC());
    }

    FeishuResultOutboxProcessor(
            FeishuResultOutboxRepository repository,
            FeishuReplyClient replyClient,
            FeishuProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.replyClient = replyClient;
        this.properties = properties.outbox();
        this.clock = clock;
    }

    public void process(String id) {
        FeishuResultOutboxMessage claimed = repository.claim(
                id, clock.instant(), properties.processingTimeout(),
                properties.maxAttempts());
        if (claimed == null) {
            return;
        }
        try {
            replyClient.replyText(claimed.openId(), claimed.text());
            repository.markSent(id, clock.instant());
        } catch (Exception exception) {
            Instant failedAt = clock.instant();
            boolean terminal = claimed.attemptCount() >= properties.maxAttempts();
            repository.markFailed(
                    id, safeError(exception), failedAt,
                    failedAt.plus(properties.retryDelay()), terminal);
        }
    }

    private String safeError(Exception exception) {
        String message = sanitizer.preview(exception.getMessage());
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
