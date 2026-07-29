package com.hkdzagent.agent.tool;

public record ToolExecutionFence(String workerId, long leaseEpoch) {

    public ToolExecutionFence {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (leaseEpoch < 1) {
            throw new IllegalArgumentException("leaseEpoch must be positive");
        }
    }
}
