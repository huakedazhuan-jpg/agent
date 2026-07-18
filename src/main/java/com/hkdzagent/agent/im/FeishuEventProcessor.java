package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.LLMClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Component
public class FeishuEventProcessor {

    private static final String ERROR_REPLY = "抱歉，消息处理失败，请稍后再试。";

    private final ObjectMapper objectMapper;
    private final LLMClient llmClient;
    private final FeishuReplyClient feishuReplyClient;
    private final Executor executor;

    public FeishuEventProcessor(
            ObjectMapper objectMapper,
            LLMClient llmClient,
            FeishuReplyClient feishuReplyClient,
            @Qualifier("feishuTaskExecutor") Executor executor
    ) {
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.feishuReplyClient = feishuReplyClient;
        this.executor = executor;
    }

    public void processAsync(JsonNode payload) {
        JsonNode eventCopy = payload.deepCopy();
        executor.execute(() -> process(eventCopy));
    }

    private void process(JsonNode payload) {
        String openId = "";
        try {
            JsonNode event = payload.path("event");
            JsonNode message = event.path("message");
            openId = event.path("sender").path("sender_id").path("open_id").asText();

            String contentStr = message.path("content").asText();
            JsonNode contentNode = objectMapper.readTree(contentStr);
            String userText = contentNode.path("text").asText();

            String answer = llmClient.askWithTools(userText, openId);
            feishuReplyClient.replyText(openId, answer);
        } catch (Exception e) {
            if (!openId.isBlank()) {
                feishuReplyClient.replyText(openId, ERROR_REPLY);
            }
        }
    }
}
