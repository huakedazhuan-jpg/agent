package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.memory.MemoryProcessingJob;
import com.hkdzagent.agent.memory.MemoryProcessingJobRepository;
import com.hkdzagent.agent.memory.OwnedConversationId;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public class AgentCompletionService {

    private final AgentRuntimeService runtimeService;
    private final FeishuResultOutboxService outboxService;
    private final MemoryProcessingJobRepository memoryJobs;

    public AgentCompletionService(
            AgentRuntimeService runtimeService,
            FeishuResultOutboxService outboxService
    ) {
        this(runtimeService, outboxService, MemoryProcessingJobRepository.NOOP);
    }

    public AgentCompletionService(
            AgentRuntimeService runtimeService,
            FeishuResultOutboxService outboxService,
            MemoryProcessingJobRepository memoryJobs
    ) {
        this.runtimeService = runtimeService;
        this.outboxService = outboxService;
        this.memoryJobs = memoryJobs == null
                ? MemoryProcessingJobRepository.NOOP
                : memoryJobs;
    }

    @Transactional
    public AgentRunCompletion complete(
            String runId, String workerId, long leaseEpoch, String answer
    ) {
        AgentRun completed = runtimeService.complete(runId, workerId, leaseEpoch, answer);
        AgentRunEvent event = runtimeService.appendSystemEvent(
                runId, AgentRunEventType.RUN_COMPLETED,
                Map.of("answer", answer == null ? "" : answer));
        outboxService.enqueueCompletedRun(completed);
        OwnedConversationId conversation =
                OwnedConversationId.decodeOrLegacy(completed.conversationId());
        memoryJobs.enqueue(
                completed.ownerKey(), conversation.externalId(), completed.runId(),
                MemoryProcessingJob.JobType.UPDATE_SUMMARY);
        memoryJobs.enqueue(
                completed.ownerKey(), conversation.externalId(), completed.runId(),
                MemoryProcessingJob.JobType.EXTRACT_LONG_TERM_MEMORY);
        return new AgentRunCompletion(completed, event);
    }
}
