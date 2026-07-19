package com.hkdzagent.agent.memory;

import com.hkdzagent.agent.security.ActorIdentity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record OwnedConversationId(ActorIdentity owner, String externalId) {

    private static final String PREFIX = "owned:v1:";

    public OwnedConversationId {
        if (owner == null) {
            throw new IllegalArgumentException("conversation owner must not be null");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("external conversation id must not be blank");
        }
        externalId = externalId.trim();
    }

    public String encode() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX
                + encoder.encodeToString(owner.key().getBytes(StandardCharsets.UTF_8))
                + ":"
                + encoder.encodeToString(externalId.getBytes(StandardCharsets.UTF_8));
    }

    public static OwnedConversationId decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            throw new IllegalArgumentException("owned conversation id has an unsupported format");
        }
        String payload = encoded.substring(PREFIX.length());
        int separator = payload.indexOf(':');
        if (separator <= 0 || separator == payload.length() - 1) {
            throw new IllegalArgumentException("owned conversation id is malformed");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String ownerKey = new String(
                    decoder.decode(payload.substring(0, separator)),
                    StandardCharsets.UTF_8
            );
            String externalId = new String(
                    decoder.decode(payload.substring(separator + 1)),
                    StandardCharsets.UTF_8
            );
            return new OwnedConversationId(new ActorIdentity(ownerKey), externalId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("owned conversation id is malformed", exception);
        }
    }

    public static OwnedConversationId decodeOrLegacy(String value) {
        if (value != null && value.startsWith(PREFIX)) {
            return decode(value);
        }
        return new OwnedConversationId(ActorIdentity.legacyUnowned(), value);
    }
}
