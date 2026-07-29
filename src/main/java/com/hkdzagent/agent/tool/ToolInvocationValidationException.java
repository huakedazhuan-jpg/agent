package com.hkdzagent.agent.tool;

public class ToolInvocationValidationException extends IllegalArgumentException {
    public ToolInvocationValidationException(String message) {
        super(message);
    }

    public ToolInvocationValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
