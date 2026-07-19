package com.hkdzagent.agent.runtime;

import java.util.EnumSet;
import java.util.Set;

public enum AgentRunStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(AgentRunStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return allowedTargets().contains(target);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    private Set<AgentRunStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED);
            case WAITING_APPROVAL -> EnumSet.of(RUNNING, FAILED, CANCELLED);
            case COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(AgentRunStatus.class);
        };
    }
}
