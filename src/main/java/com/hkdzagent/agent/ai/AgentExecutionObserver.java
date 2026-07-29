package com.hkdzagent.agent.ai;

import com.hkdzagent.agent.loop.AgentObservation;
import com.hkdzagent.agent.context.ContextEnvelope;

public interface AgentExecutionObserver {

    AgentExecutionObserver NOOP = new AgentExecutionObserver() {
    };

    default void modelStarted(int step) {
    }

    default void contextAssembled(int step, ContextEnvelope envelope) {
    }

    default String contextOwnerKey() {
        return null;
    }

    default String contextRunId() {
        return null;
    }

    default void tokenDelta(int step, String delta) {
    }

    default void modelCompleted(int step) {
    }

    default void toolCallRequested(int step, String toolName, String arguments) {
    }

    default void toolCallRequested(
            int step, String toolCallId, String toolName, String arguments
    ) {
        toolCallRequested(step, toolName, arguments);
    }

    default boolean requiresApproval(int step, String toolName, String arguments) {
        return false;
    }

    default boolean requiresApproval(
            int step, String toolCallId, String toolName, String arguments
    ) {
        return requiresApproval(step, toolName, arguments);
    }

    default void approvalRequired(
            int step,
            String toolName,
            String arguments,
            String checkpointJson
    ) {
    }

    default void approvalRequired(
            int step,
            String toolCallId,
            String toolName,
            String arguments,
            String checkpointJson
    ) {
        approvalRequired(step, toolName, arguments, checkpointJson);
    }

    default AgentObservation executeTool(
            int step,
            String toolCallId,
            String toolName,
            String arguments
    ) {
        return null;
    }

    default void toolStarted(int step, String toolName) {
    }

    default void toolCompleted(int step, String toolName, boolean success, String observation) {
    }
}
