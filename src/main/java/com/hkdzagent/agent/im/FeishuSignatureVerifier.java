package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

@Component
public class FeishuSignatureVerifier {

    private final String verificationToken;
    private final String encryptKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FeishuSignatureVerifier(
            @Value("${feishu.verification-token:}") String verificationToken,
            @Value("${feishu.encrypt-key:}") String encryptKey
    ) {
        this.verificationToken = normalize(verificationToken);
        this.encryptKey = normalize(encryptKey);
    }

    public boolean verify(Map<String, String> headers, String body) {
        if (!verificationToken.isBlank() && !verificationToken.equals(payloadToken(body))) {
            return false;
        }

        String signature = header(headers, "X-Lark-Signature");
        String timestamp = header(headers, "X-Lark-Request-Timestamp");
        String nonce = header(headers, "X-Lark-Request-Nonce");
        if (signature == null && timestamp == null && nonce == null) {
            return true;
        }
        if (encryptKey.isBlank() || signature == null || timestamp == null || nonce == null) {
            return false;
        }
        return MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature(timestamp, nonce, body).getBytes(StandardCharsets.UTF_8));
    }

    private String expectedSignature(String timestamp, String nonce, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((timestamp + nonce + encryptKey + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("failed to verify Feishu signature", e);
        }
    }

    private String payloadToken(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("token")) {
                return root.path("token").asText();
            }
            return root.path("header").path("token").asText();
        } catch (Exception e) {
            return "";
        }
    }

    private String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String expected = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(expected)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
