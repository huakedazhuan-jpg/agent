package com.hkdzagent.agent.tool;

public record ToolResult(Status status, String message) {

    public enum Status {
        SUCCESS,
        REJECTED,
        FAILED
    }

    public ToolResult {
        message = message == null ? "" : message;
    }

    public static ToolResult success(String message) {
        return new ToolResult(Status.SUCCESS, message);
    }

    public static ToolResult rejected(String reason) {
        String message = reason == null ? "" : reason.strip();
        if (message.isEmpty()) {
            return new ToolResult(Status.REJECTED, "rejected");
        }
        if (message.toLowerCase().startsWith("rejected:")) {
            return new ToolResult(Status.REJECTED, message);
        }
        return new ToolResult(Status.REJECTED, "rejected: " + message);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(Status.FAILED, message);
    }
}
