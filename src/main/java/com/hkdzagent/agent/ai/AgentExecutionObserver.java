package com.hkdzagent.agent.ai;

public interface AgentExecutionObserver {

    AgentExecutionObserver NOOP = new AgentExecutionObserver() {
    };

    default void modelStarted(int step) {
    }

    default void tokenDelta(int step, String delta) {
    }

    default void modelCompleted(int step) {
    }

    default void toolCallRequested(int step, String toolName, String arguments) {
    }

    default boolean requiresApproval(int step, String toolName, String arguments) {
        return false;
    }

    default void approvalRequired(
            int step,
            String toolName,
            String arguments,
            String checkpointJson
    ) {
    }

    default void toolStarted(int step, String toolName) {
    }

    default void toolCompleted(int step, String toolName, boolean success, String observation) {
    }
}
