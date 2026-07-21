package com.hkdzagent.agent.tool;

import java.time.Duration;

public record ToolRetryPolicy(int maxAttempts, Duration delay) {

    public ToolRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
    }

    public static ToolRetryPolicy none() {
        return new ToolRetryPolicy(1, Duration.ZERO);
    }

    public static ToolRetryPolicy fixed(int maxAttempts, Duration delay) {
        return new ToolRetryPolicy(maxAttempts, delay);
    }
}
