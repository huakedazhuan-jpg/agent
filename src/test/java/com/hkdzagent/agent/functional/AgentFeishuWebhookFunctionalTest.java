package com.hkdzagent.agent.functional;

import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.im.FeishuAsyncConfig;
import com.hkdzagent.agent.im.FeishuEventInboxService;
import com.hkdzagent.agent.im.FeishuEventProcessor;
import com.hkdzagent.agent.im.FeishuInboxConfig;
import com.hkdzagent.agent.im.FeishuReplyClient;
import com.hkdzagent.agent.im.FeishuSignatureVerifier;
import com.hkdzagent.agent.im.FeishuWebhookController;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeishuWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        FeishuSignatureVerifier.class,
        FeishuEventInboxService.class,
        FeishuEventProcessor.class,
        FeishuAsyncConfig.class,
        FeishuInboxConfig.class
})
@TestPropertySource(properties = {
        "feishu.verification-token=test-verification-token",
        "feishu.encrypt-key=test-encrypt-key",
        "feishu.inbox.max-attempts=1"
})
class AgentFeishuWebhookFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LLMClient llmClient;

    @MockitoBean
    private FeishuReplyClient feishuReplyClient;

    @Test
    void urlVerificationReturnsChallengeWithoutCallingAgentOrFeishuReplyApi() throws Exception {
        mockMvc.perform(post("/api/feishu/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "url_verification",
                                  "token": "test-verification-token",
                                  "challenge": "challenge-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("challenge-token"));

        verifyNoInteractions(llmClient, feishuReplyClient);
    }

    @Test
    void rejectsMessageEventWhenSignatureIsInvalid() throws Exception {
        mockMvc.perform(post("/api/feishu/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Request-Timestamp", "1710000000")
                        .header("X-Lark-Request-Nonce", "nonce")
                        .header("X-Lark-Signature", "invalid-signature")
                        .content(messageEvent("event-invalid-signature", "message-invalid-signature", "hello")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(llmClient, feishuReplyClient);
    }

    @Test
    void ignoresDuplicateMessageEvents() throws Exception {
        when(llmClient.askWithTools("hello", conversationId("open_1"))).thenReturn("answer");
        String payload = messageEvent("event-duplicate", "message-duplicate", "hello");

        mockMvc.perform(post("/api/feishu/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/feishu/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(llmClient, timeout(1000).times(1)).askWithTools("hello", conversationId("open_1"));
        verify(feishuReplyClient, timeout(1000).times(1)).replyText("open_1", "answer");
    }

    @Test
    void sendsUserSafeErrorReplyWhenAgentProcessingFails() throws Exception {
        when(llmClient.askWithTools("hello", conversationId("open_1")))
                .thenThrow(new IllegalStateException("model exploded"));

        mockMvc.perform(post("/api/feishu/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageEvent("event-agent-failure", "message-agent-failure", "hello")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(feishuReplyClient, timeout(1000))
                .replyText("open_1", "抱歉，消息处理失败，请稍后再试。");
        verify(feishuReplyClient, never()).replyText("open_1", "model exploded");
    }

    private static String messageEvent(String eventId, String messageId, String text) {
        return """
                {
                  "schema": "2.0",
                  "header": {
                    "event_id": "%s",
                    "event_type": "im.message.receive_v1",
                    "token": "test-verification-token"
                  },
                  "event": {
                    "sender": {
                      "sender_id": {
                        "open_id": "open_1"
                      }
                    },
                    "message": {
                      "message_id": "%s",
                      "content": "{\\"text\\":\\"%s\\"}"
                    }
                  }
                }
                """.formatted(eventId, messageId, text);
    }

    private String conversationId(String openId) {
        return new OwnedConversationId(ActorIdentity.feishu(openId), openId).encode();
    }
}
