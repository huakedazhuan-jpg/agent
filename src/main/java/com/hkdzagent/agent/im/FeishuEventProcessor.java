package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class FeishuEventProcessor {

    private static final String ERROR_REPLY = "抱歉，消息处理失败，请稍后再试。";

    private final ObjectMapper objectMapper;
    private final LLMClient llmClient;
    private final FeishuReplyClient feishuReplyClient;
    private final FeishuEventInboxRepository inboxRepository;
    private final FeishuProperties.Inbox inboxProperties;
    private final Executor executor;
    private final Clock clock;
    private final AgentTraceSanitizer errorSanitizer = new AgentTraceSanitizer(500);

    @Autowired
    public FeishuEventProcessor(
            ObjectMapper objectMapper,
            LLMClient llmClient,
            FeishuReplyClient feishuReplyClient,
            FeishuEventInboxRepository inboxRepository,
            FeishuProperties properties,
            @Qualifier("feishuTaskExecutor") Executor executor
    ) {
        this(objectMapper, llmClient, feishuReplyClient, inboxRepository, properties, executor, Clock.systemUTC());
    }

    FeishuEventProcessor(
            ObjectMapper objectMapper,
            LLMClient llmClient,
            FeishuReplyClient feishuReplyClient,
            FeishuEventInboxRepository inboxRepository,
            FeishuProperties properties,
            Executor executor,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.feishuReplyClient = feishuReplyClient;
        this.inboxRepository = inboxRepository;
        this.inboxProperties = properties.inbox();
        this.executor = executor;
        this.clock = clock;
    }

    public void processAsync(String eventId) {
        try {
            executor.execute(() -> process(eventId));
        } catch (RejectedExecutionException ignored) {
            // The event remains durable and the recovery scheduler will submit it again.
        }
    }

    void process(String eventId) {
        Instant now = clock.instant();
        FeishuInboxEvent inboxEvent = inboxRepository.claim(
                eventId,
                now,
                inboxProperties.processingTimeout(),
                inboxProperties.maxAttempts()
        );
        if (inboxEvent == null) {
            return;
        }

        try {
            JsonNode payload = objectMapper.readTree(inboxEvent.payload());
            JsonNode event = payload.path("event");
            JsonNode message = event.path("message");
            String openId = inboxEvent.openId();

            String contentStr = message.path("content").asText();
            JsonNode contentNode = objectMapper.readTree(contentStr);
            String userText = contentNode.path("text").asText();

            String conversationId = new OwnedConversationId(ActorIdentity.feishu(openId), openId).encode();
            String answer = llmClient.askWithTools(userText, conversationId);
            feishuReplyClient.replyText(openId, answer);
            inboxRepository.markProcessed(eventId, clock.instant());
        } catch (Exception e) {
            boolean terminal = inboxEvent.retryCount() >= inboxProperties.maxAttempts();
            Instant failedAt = clock.instant();
            Instant nextAttemptAt = failedAt.plus(inboxProperties.retryDelay());
            inboxRepository.markFailed(eventId, safeError(e), failedAt, nextAttemptAt, terminal);
            if (terminal && !inboxEvent.openId().isBlank()) {
                try {
                    feishuReplyClient.replyText(inboxEvent.openId(), ERROR_REPLY);
                } catch (Exception ignored) {
                    // The durable DEAD state remains available for operational inspection.
                }
            }
        }
    }

    private String safeError(Exception exception) {
        String message = errorSanitizer.preview(exception.getMessage());
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
