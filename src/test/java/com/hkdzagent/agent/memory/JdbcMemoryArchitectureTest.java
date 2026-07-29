package com.hkdzagent.agent.memory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMemoryArchitectureTest {

    @Test
    void messageMigrationUsesBigintAtomicReservationAndRunIdempotency() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/postgresql/V16__conversation_message_ordering.sql"));

        assertThat(migration)
                .contains("next_message_index BIGINT")
                .contains("ALTER COLUMN message_index TYPE BIGINT")
                .contains("ux_agent_messages_conversation_message_index")
                .contains("ux_agent_messages_run_message_type");
        String repository = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/memory/JdbcConversationMessageRepository.java"));
        assertThat(repository)
                .contains("SET next_message_index = next_message_index + :count")
                .contains("RETURNING next_message_index");
    }

    @Test
    void jobsAreDeduplicatedClaimedWithSkipLockedAndBecomeDeadAfterRetries()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/postgresql/V19__memory_processing_jobs.sql"));
        String repository = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/memory/JdbcMemoryProcessingJobRepository.java"));

        assertThat(migration).contains("ux_memory_processing_jobs_deduplication");
        assertThat(repository)
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("attempt_count >= :maxAttempts")
                .contains("'DEAD'");
    }

    @Test
    void summaryAndMemoryWritesAreOwnerScopedAndOptimisticallyLocked() throws IOException {
        String summaryRepository = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/memory/JdbcConversationSummaryRepository.java"));
        String memoryMigration = Files.readString(Path.of(
                "src/main/resources/db/migration/postgresql/V18__long_term_memory.sql"));

        assertThat(summaryRepository)
                .contains("owner_key = :ownerKey")
                .contains("version = :expectedVersion");
        assertThat(memoryMigration)
                .contains("ux_agent_memory_owner_type_key")
                .contains("(owner_key, memory_type, normalized_key)");
    }
}
