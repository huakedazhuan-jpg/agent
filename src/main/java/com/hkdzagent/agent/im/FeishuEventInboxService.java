package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class FeishuEventInboxService {

    private final FeishuEventInboxRepository repository;
    private final Clock clock;

    @Autowired
    public FeishuEventInboxService(FeishuEventInboxRepository repository) {
        this(repository, Clock.systemUTC());
    }

    FeishuEventInboxService(FeishuEventInboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public String receive(JsonNode payload, String rawPayload) {
        String eventId = eventId(payload, rawPayload);
        Instant receivedAt = clock.instant();
        repository.receive(new FeishuInboxEvent(
                eventId,
                payload.path("header").path("event_type").asText("unknown"),
                payload.path("event").path("sender").path("sender_id").path("open_id").asText(),
                rawPayload,
                FeishuInboxEvent.Status.RECEIVED,
                receivedAt,
                null,
                null,
                receivedAt,
                0,
                null
        ));
        return eventId;
    }

    private String eventId(JsonNode payload, String rawPayload) {
        String eventId = payload.path("header").path("event_id").asText();
        if (!eventId.isBlank()) {
            return eventId;
        }
        String messageId = payload.path("event").path("message").path("message_id").asText();
        if (!messageId.isBlank()) {
            return messageId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPayload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to derive Feishu event id", e);
        }
    }
}
