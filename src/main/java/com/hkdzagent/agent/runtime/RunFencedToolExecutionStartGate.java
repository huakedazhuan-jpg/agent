package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.tool.ToolExecutionFence;
import com.hkdzagent.agent.tool.ToolExecutionJournalEntry;
import com.hkdzagent.agent.tool.ToolExecutionJournalRepository;
import com.hkdzagent.agent.tool.ToolExecutionStartGate;

public class RunFencedToolExecutionStartGate implements ToolExecutionStartGate {

    private final AgentRunRepository runRepository;
    private final ToolExecutionJournalRepository journalRepository;

    public RunFencedToolExecutionStartGate(
            AgentRunRepository runRepository,
            ToolExecutionJournalRepository journalRepository
    ) {
        this.runRepository = runRepository;
        this.journalRepository = journalRepository;
    }

    @Override
    public Decision reserve(
            ToolExecutionJournalEntry candidate,
            ToolExecutionFence fence
    ) {
        if (fence == null) {
            return Decision.denied();
        }
        ToolExecutionJournalRepository.Reservation reservation =
                runRepository.executeWithActiveLease(
                        candidate.runId(), fence.workerId(), fence.leaseEpoch(),
                        candidate.startedAt(), () -> journalRepository.reserve(candidate));
        return reservation == null
                ? Decision.denied()
                : Decision.allowed(reservation);
    }
}
