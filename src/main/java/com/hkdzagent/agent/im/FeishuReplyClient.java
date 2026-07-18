package com.hkdzagent.agent.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class FeishuReplyClient {

    private final FeishuTenantTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public FeishuReplyClient(FeishuTenantTokenProvider tokenProvider, ObjectMapper objectMapper) {
        this(tokenProvider, objectMapper, RestClient.create());
    }

    FeishuReplyClient(FeishuTenantTokenProvider tokenProvider, ObjectMapper objectMapper, RestClient restClient) {
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public void replyText(String openId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("receive_id", openId);
        body.put("msg_type", "text");
        body.put("content", contentJson(text));

        restClient.post()
                .uri("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id")
                .header("Authorization", "Bearer " + tokenProvider.tenantAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private String contentJson(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of("text", text == null ? "" : text));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to build Feishu reply content", e);
        }
    }
}
