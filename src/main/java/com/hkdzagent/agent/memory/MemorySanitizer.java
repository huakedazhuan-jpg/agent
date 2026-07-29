package com.hkdzagent.agent.memory;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class MemorySanitizer {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|密码|密钥|token)"
                    + "\\s*[:=：]\\s*\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]{12,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

    public Optional<String> sanitize(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String stripped = content.strip();
        if (SECRET_ASSIGNMENT.matcher(stripped).find()
                || BEARER.matcher(stripped).find()
                || PRIVATE_KEY.matcher(stripped).find()) {
            return Optional.empty();
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.contains("忽略系统指令") && (lower.contains("密钥") || lower.contains("token"))) {
            return Optional.empty();
        }
        return Optional.of(stripped.length() <= 2000 ? stripped : stripped.substring(0, 2000));
    }

    public String normalizedKey(String content) {
        return content.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", " ")
                .strip();
    }
}
