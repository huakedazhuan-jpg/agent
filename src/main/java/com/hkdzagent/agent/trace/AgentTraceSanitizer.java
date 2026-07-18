package com.hkdzagent.agent.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class AgentTraceSanitizer {

    private static final String REDACTED = "[redacted]";
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[^\\s,;}]+");
    private static final Pattern ENV_SECRET_PATTERN = Pattern.compile("(?i)(moonshot_api_key|api[_-]?key|token|secret)\\s*=\\s*[^\\s,;}]+");

    private final int maxPreviewChars;

    public AgentTraceSanitizer(int maxPreviewChars) {
        this.maxPreviewChars = Math.max(1, maxPreviewChars);
    }

    public Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                sanitized.put(key, REDACTED);
            } else {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        return sanitized;
    }

    public String preview(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = sanitizeString(value);
        if (sanitized.length() <= maxPreviewChars) {
            return sanitized;
        }
        return sanitized.substring(0, maxPreviewChars) + "...";
    }

    public String sanitizeString(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = BEARER_PATTERN.matcher(value).replaceAll("Bearer " + REDACTED);
        return ENV_SECRET_PATTERN.matcher(sanitized).replaceAll("$1=" + REDACTED);
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof String text) {
            return preview(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> {
                String key = String.valueOf(nestedKey);
                nested.put(key, isSensitiveKey(key) ? REDACTED : sanitizeValue(nestedValue));
            });
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            iterable.forEach(item -> items.add(sanitizeValue(item)));
            return List.copyOf(items);
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
                || normalized.contains("api_key")
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("moonshot_api_key");
    }
}
