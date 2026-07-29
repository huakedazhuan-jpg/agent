package com.hkdzagent.agent.memory;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MemoryRetriever {

    private final MemoryRepository repository;
    private final MemorySanitizer sanitizer;
    private final Clock clock;

    public MemoryRetriever(MemoryRepository repository, MemorySanitizer sanitizer) {
        this(repository, sanitizer, Clock.systemUTC());
    }

    MemoryRetriever(MemoryRepository repository, MemorySanitizer sanitizer, Clock clock) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    public List<ScoredMemory> retrieve(String ownerKey, String query, int limit) {
        String normalized = sanitizer.normalizedKey(query == null ? "" : query);
        int candidateLimit = Math.max(limit * 3, limit);
        Map<java.util.UUID, MemoryItem> unique = new LinkedHashMap<>();
        if (!normalized.isBlank()) {
            repository.search(ownerKey, normalized, candidateLimit)
                    .forEach(item -> unique.put(item.id(), item));
        }
        repository.findActive(ownerKey, candidateLimit)
                .forEach(item -> unique.putIfAbsent(item.id(), item));
        return unique.values().stream()
                .filter(item -> item.ownerKey().equals(ownerKey))
                .map(item -> new ScoredMemory(item, score(item, normalized)))
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(scored -> scored.item().id()))
                .limit(Math.max(0, limit))
                .toList();
    }

    private double score(MemoryItem item, String query) {
        String key = item.normalizedKey().toLowerCase(Locale.ROOT);
        double relevance = key.equals(query) ? 1.0 : keywordOverlap(key, query);
        double ageDays = Math.max(0,
                Duration.between(item.updatedAt(), clock.instant()).toHours() / 24.0);
        double timeDecay = Math.min(0.35, ageDays / 365.0 * 0.35);
        return relevance + item.importance() * 0.4 + item.confidence() * 0.2 - timeDecay;
    }

    private double keywordOverlap(String key, String query) {
        if (query.isBlank()) {
            return 0;
        }
        long matches = java.util.Arrays.stream(key.split("\\s+"))
                .filter(word -> word.length() > 1 && query.contains(word))
                .count();
        return Math.min(0.9, matches / (double) Math.max(1, key.split("\\s+").length));
    }

    public record ScoredMemory(MemoryItem item, double score) {
    }
}
