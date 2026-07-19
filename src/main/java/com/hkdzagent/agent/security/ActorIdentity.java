package com.hkdzagent.agent.security;

import java.util.Locale;
import java.util.regex.Pattern;

public record ActorIdentity(String key) {

    public static final int MAX_KEY_LENGTH = 320;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9_-]*");

    public ActorIdentity {
        key = requireKey(key);
    }

    public static ActorIdentity user(String userId) {
        return namespaced("user", userId);
    }

    public static ActorIdentity feishu(String openId) {
        return namespaced("feishu", openId);
    }

    public static ActorIdentity localAnonymous() {
        return new ActorIdentity("local:anonymous");
    }

    public static ActorIdentity legacyUnowned() {
        return new ActorIdentity("legacy:unowned");
    }

    public static ActorIdentity namespaced(String namespace, String subject) {
        String normalizedNamespace = namespace == null
                ? ""
                : namespace.trim().toLowerCase(Locale.ROOT);
        if (!NAMESPACE.matcher(normalizedNamespace).matches()) {
            throw new IllegalArgumentException("actor namespace is invalid");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("actor subject must not be blank");
        }
        return new ActorIdentity(normalizedNamespace + ":" + subject.trim());
    }

    public String namespace() {
        return key.substring(0, key.indexOf(':'));
    }

    public String subject() {
        return key.substring(key.indexOf(':') + 1);
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("actor key must not be blank");
        }
        String normalized = value.trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("actor key must use namespace:subject format");
        }
        if (!NAMESPACE.matcher(normalized.substring(0, separator)).matches()) {
            throw new IllegalArgumentException("actor namespace is invalid");
        }
        if (normalized.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("actor key exceeds " + MAX_KEY_LENGTH + " characters");
        }
        return normalized;
    }
}
