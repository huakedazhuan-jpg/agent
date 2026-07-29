package com.hkdzagent.agent.tool;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record ToolMetadata(
        String name,
        String version,
        String inputSchema,
        ToolRiskLevel riskLevel,
        ToolApprovalPolicy approvalPolicy,
        Duration timeout,
        ToolRetryPolicy retryPolicy
) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{1,127}");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    public ToolMetadata {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("tool name is invalid");
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("tool version must use major.minor.patch");
        }
        if (inputSchema == null || inputSchema.isBlank()
                || !inputSchema.stripLeading().startsWith("{")) {
            throw new IllegalArgumentException("tool inputSchema must be a JSON object schema");
        }
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(approvalPolicy, "approvalPolicy must not be null");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("tool timeout must be positive");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    }
}
