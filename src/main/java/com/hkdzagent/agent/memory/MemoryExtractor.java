package com.hkdzagent.agent.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MemoryExtractor {

    private final MemorySanitizer sanitizer;
    private final Clock clock;

    public MemoryExtractor(MemorySanitizer sanitizer) {
        this(sanitizer, Clock.systemUTC());
    }

    MemoryExtractor(MemorySanitizer sanitizer, Clock clock) {
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    public List<MemoryCandidate> extract(
            StoredConversationMessage userMessage,
            StoredConversationMessage finalAssistantMessage
    ) {
        List<MemoryCandidate> candidates = new ArrayList<>();
        if (eligible(userMessage, "USER", "RUN_USER")) {
            candidates.addAll(extractExplicitUserStatements(userMessage.content()));
        }
        // The first version intentionally does not infer memories from assistant text.
        // Accepting the parameter makes the successful-run boundary explicit.
        if (!eligible(finalAssistantMessage, "ASSISTANT", "RUN_ASSISTANT")) {
            return deduplicate(candidates);
        }
        return deduplicate(candidates);
    }

    public List<MemoryItem> extractAndSave(
            String ownerKey,
            UUID conversationId,
            StoredConversationMessage userMessage,
            StoredConversationMessage assistantMessage,
            MemoryRepository repository
    ) {
        Instant now = clock.instant();
        return extract(userMessage, assistantMessage).stream()
                .map(candidate -> repository.upsert(new MemoryItem(
                        UUID.randomUUID(), ownerKey, candidate.type(), candidate.content(),
                        candidate.normalizedKey(), conversationId,
                        userMessage == null ? null : userMessage.id(),
                        candidate.importance(), candidate.confidence(),
                        MemoryItem.Status.ACTIVE, now, now)))
                .toList();
    }

    private List<MemoryCandidate> extractExplicitUserStatements(String content) {
        List<MemoryCandidate> result = new ArrayList<>();
        for (String raw : content.split("(?<=[。！？!?;；])|\\n+")) {
            sanitizer.sanitize(raw).ifPresent(statement -> {
                MemoryType type = classify(statement);
                if (type != null) {
                    String key = sanitizer.normalizedKey(statement);
                    if (!key.isBlank()) {
                        result.add(new MemoryCandidate(
                                type, stripTrailingPunctuation(statement), key,
                                importance(type), 0.95));
                    }
                }
            });
        }
        return result;
    }

    private MemoryType classify(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("以后") || value.contains("尽量") || value.contains("优先")
                || lower.contains("prefer") || lower.contains("always answer")) {
            return MemoryType.USER_PREFERENCE;
        }
        if (value.startsWith("我是") || value.startsWith("我叫")
                || lower.startsWith("i am ") || lower.startsWith("i'm ")) {
            return MemoryType.USER_PROFILE;
        }
        if (value.contains("决定") || value.contains("已确认")
                || lower.contains("decided") || lower.contains("we chose")) {
            return MemoryType.PAST_DECISION;
        }
        if (value.contains("项目") && (value.contains("使用") || value.contains("采用"))
                || lower.contains("project uses")) {
            return MemoryType.PROJECT_FACT;
        }
        return null;
    }

    private boolean eligible(StoredConversationMessage message, String role, String source) {
        return message != null && role.equalsIgnoreCase(message.role())
                && source.equals(message.source());
    }

    private List<MemoryCandidate> deduplicate(List<MemoryCandidate> candidates) {
        Map<String, MemoryCandidate> unique = new LinkedHashMap<>();
        for (MemoryCandidate candidate : candidates) {
            unique.put(candidate.type() + "\0" + candidate.normalizedKey(), candidate);
        }
        return List.copyOf(unique.values());
    }

    private String stripTrailingPunctuation(String value) {
        return value.replaceFirst("[。！？!?;；]+$", "").strip();
    }

    private double importance(MemoryType type) {
        return type == MemoryType.USER_PREFERENCE ? 0.8 : 0.7;
    }
}
