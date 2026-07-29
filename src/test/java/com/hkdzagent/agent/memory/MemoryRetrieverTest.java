package com.hkdzagent.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRetrieverTest {

    @Test
    void retrievesRelevantMemoryWithoutCrossingOwnerBoundary() {
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        repository.upsert(item("user:a", "以后示例优先使用 spring boot"));
        repository.upsert(item("user:b", "以后示例优先使用 spring boot"));
        MemoryRetriever retriever = new MemoryRetriever(repository, new MemorySanitizer());

        assertThat(retriever.retrieve("user:a", "请给 spring boot 示例", 10))
                .extracting(result -> result.item().ownerKey())
                .containsOnly("user:a");
    }

    private MemoryItem item(String owner, String content) {
        return new MemoryItem(
                UUID.randomUUID(), owner, MemoryType.USER_PREFERENCE, content, content,
                null, null, .8, .95, MemoryItem.Status.ACTIVE, Instant.now(), Instant.now());
    }
}
