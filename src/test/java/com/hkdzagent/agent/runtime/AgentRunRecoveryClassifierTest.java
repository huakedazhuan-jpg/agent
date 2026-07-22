package com.hkdzagent.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunRecoveryClassifierTest {

    private final AgentRunRecoveryClassifier classifier = new AgentRunRecoveryClassifier();

    @Test
    void restartsOnlyWhenNoToolStartedAndAttemptsRemain() {
        assertThat(classifier.classify(
                new AgentRunRecoveryEvidence(false, 0), 3))
                .isEqualTo(AgentRunRecoveryDecision.SAFE_RESTART);
        assertThat(classifier.classify(
                new AgentRunRecoveryEvidence(true, 0), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_TOOL_EFFECT);
        assertThat(classifier.classify(
                new AgentRunRecoveryEvidence(false, 3), 3))
                .isEqualTo(AgentRunRecoveryDecision.BLOCKED_ATTEMPTS);
    }
}
