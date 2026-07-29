package com.hkdzagent.agent.runtime;

import com.hkdzagent.agent.tool.ToolExecutionJournalEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunRecoveryClassifierTest {

    private final AgentRunRecoveryClassifier classifier = new AgentRunRecoveryClassifier();

    @Test
    void restartsOnlyWhenNoToolStartedAndAttemptsRemain() {
        assertThat(classifier.classify(
                new AgentRunRecoveryEvidence(false, 0),
                new ToolExecutionJournalEvidence(0, 0), 3))
                .isEqualTo(AgentRunRecoveryDecision.SAFE_RESTART);
        assertThat(classifier.classify(
                new AgentRunRecoveryEvidence(true, 0),
                new ToolExecutionJournalEvidence(0, 0), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_TOOL_JOURNAL_MISSING);
        assertThat(classifier.classify(
                new AgentRunRecoveryEvidence(false, 3),
                new ToolExecutionJournalEvidence(0, 0), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_ATTEMPTS);
    }

    @Test
    void journalEvidenceBlocksRestartEvenWithoutToolStartedEvent() {
        AgentRunRecoveryEvidence noToolEvent = new AgentRunRecoveryEvidence(false, 0);

        assertThat(classifier.classify(
                noToolEvent, new ToolExecutionJournalEvidence(1, 0), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_TOOL_EXECUTION_UNCERTAIN);
        assertThat(classifier.classify(
                noToolEvent, new ToolExecutionJournalEvidence(0, 1), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_TOOL_CHECKPOINT_MISSING);
        assertThat(classifier.classify(
                noToolEvent, new ToolExecutionJournalEvidence(1, 1), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_TOOL_EXECUTION_UNCERTAIN);
    }
}
