package com.hkdzagent.agent.memory;

import java.util.List;
import java.util.UUID;

public interface MemoryRepository {

    MemoryItem upsert(MemoryItem item);

    List<MemoryItem> findActive(String ownerKey, int limit);

    List<MemoryItem> search(String ownerKey, String normalizedQuery, int limit);

    boolean delete(String ownerKey, UUID memoryId);

    int deleteAll(String ownerKey);
}
