package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FeishuWebhookController {

    private final ObjectMapper objectMapper;
    private final FeishuSignatureVerifier signatureVerifier;
    private final FeishuEventInboxService eventInboxService;
    private final FeishuEventProcessor eventProcessor;

    public FeishuWebhookController(
            ObjectMapper objectMapper,
            FeishuSignatureVerifier signatureVerifier,
            FeishuEventInboxService eventInboxService,
            FeishuEventProcessor eventProcessor
    ) {
        this.objectMapper = objectMapper;
        this.signatureVerifier = signatureVerifier;
        this.eventInboxService = eventInboxService;
        this.eventProcessor = eventProcessor;
    }

    @PostMapping("/api/feishu/webhook")
    public ResponseEntity<Map<String, Object>> receiveFeishuMessage(
            @RequestHeader Map<String, String> headers,
            @RequestBody String body
    ) throws Exception {
        if (!signatureVerifier.verify(headers, body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "msg", "invalid feishu signature"));
        }

        JsonNode payload = objectMapper.readTree(body);
        if (payload.has("type") && "url_verification".equals(payload.get("type").asText())) {
            return ResponseEntity.ok(Map.of("challenge", payload.path("challenge").asText()));
        }

        if (isMessageEvent(payload)) {
            String eventId = eventInboxService.receive(payload, body);
            eventProcessor.processAsync(eventId);
        }

        return ResponseEntity.ok(Map.of("code", 0, "msg", "success"));
    }

    private boolean isMessageEvent(JsonNode payload) {
        return "im.message.receive_v1".equals(payload.path("header").path("event_type").asText());
    }

}
