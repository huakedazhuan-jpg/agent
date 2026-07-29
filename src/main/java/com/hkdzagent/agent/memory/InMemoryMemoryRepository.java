package com.hkdzagent.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryMemoryRepository implements MemoryRepository {

    private final Map<String, MemoryItem> items = new LinkedHashMap<>();

    @Override
    public synchronized MemoryItem upsert(MemoryItem item) {
        String key = item.ownerKey() + "\0" + item.memoryType() + "\0" + item.normalizedKey();
        MemoryItem existing = items.get(key);
        if (existing == null) {
            items.put(key, item);
            return item;
        }
        MemoryItem merged = new MemoryItem(
                existing.id(), existing.ownerKey(), existing.memoryType(), item.content(),
                existing.normalizedKey(), item.sourceConversationId(), item.sourceMessageId(),
                Math.max(existing.importance(), item.importance()),
                Math.max(existing.confidence(), item.confidence()),
                MemoryItem.Status.ACTIVE, existing.createdAt(), Instant.now());
        items.put(key, merged);
        return merged;
    }

    @Override
    public synchronized List<MemoryItem> findActive(String ownerKey, int limit) {
        return items.values().stream()
                .filter(item -> ownerKey.equals(item.ownerKey())
                        && item.status() == MemoryItem.Status.ACTIVE)
                .sorted(Comparator.comparing(MemoryItem::updatedAt).reversed())
                .limit(limit).toList();
    }

    @Override
    public synchronized List<MemoryItem> search(
            String ownerKey, String normalizedQuery, int limit
    ) {
        return findActive(ownerKey, Integer.MAX_VALUE).stream()
                .filter(item -> normalizedQuery.contains(item.normalizedKey())
                        || item.normalizedKey().contains(normalizedQuery)
                        || sharesWord(item.normalizedKey(), normalizedQuery))
                .limit(limit).toList();
    }

    @Override
    public synchronized boolean delete(String ownerKey, UUID memoryId) {
        for (Map.Entry<String, MemoryItem> entry : new ArrayList<>(items.entrySet())) {
            MemoryItem item = entry.getValue();
            if (item.id().equals(memoryId) && item.ownerKey().equals(ownerKey)) {
                items.remove(entry.getKey());
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized int deleteAll(String ownerKey) {
        int before = items.size();
        items.entrySet().removeIf(entry -> entry.getValue().ownerKey().equals(ownerKey));
        return before - items.size();
    }

    private boolean sharesWord(String left, String right) {
        for (String word : left.split("\\s+")) {
            if (word.length() > 1 && right.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
