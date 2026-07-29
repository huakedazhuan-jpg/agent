package com.hkdzagent.agent.memory;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

public class MemoryProcessingWorker {

    private final MemoryProcessingJobRepository jobs;
    private final ConversationMessageRepository messages;
    private final ConversationSummarizer summarizer;
    private final MemoryExtractor extractor;
    private final MemoryRepository memories;
    private final Clock clock;

    public MemoryProcessingWorker(
            MemoryProcessingJobRepository jobs,
            ConversationMessageRepository messages,
            ConversationSummarizer summarizer,
            MemoryExtractor extractor,
            MemoryRepository memories
    ) {
        this(jobs, messages, summarizer, extractor, memories, Clock.systemUTC());
    }

    MemoryProcessingWorker(
            MemoryProcessingJobRepository jobs,
            ConversationMessageRepository messages,
            ConversationSummarizer summarizer,
            MemoryExtractor extractor,
            MemoryRepository memories,
            Clock clock
    ) {
        this.jobs = jobs;
        this.messages = messages;
        this.summarizer = summarizer;
        this.extractor = extractor;
        this.memories = memories;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${agent.memory-processing.poll-interval:5s}")
    public void poll() {
        for (MemoryProcessingJob job : jobs.claim(10, clock.instant())) {
            try {
                process(job);
                jobs.complete(job.id(), clock.instant());
            } catch (RuntimeException failure) {
                jobs.fail(job.id(), failure.getMessage(),
                        clock.instant().plus(Duration.ofSeconds(30)), 3);
            }
        }
    }

    private void process(MemoryProcessingJob job) {
        List<StoredConversationMessage> conversation =
                messages.findByConversationId(job.conversationId());
        if (job.jobType() == MemoryProcessingJob.JobType.UPDATE_SUMMARY) {
            summarizer.summarizeOrThrow(job.ownerKey(), job.conversationId(), conversation);
            return;
        }
        List<StoredConversationMessage> runMessages = messages.findByRun(job.runId());
        StoredConversationMessage user = runMessages.stream()
                .filter(message -> "USER".equals(message.messageType())).findFirst().orElse(null);
        StoredConversationMessage assistant = runMessages.stream()
                .filter(message -> "ASSISTANT".equals(message.messageType())).findFirst().orElse(null);
        if (user != null && assistant != null) {
            extractor.extractAndSave(
                    job.ownerKey(), job.conversationId(), user, assistant, memories);
        }
    }
}
