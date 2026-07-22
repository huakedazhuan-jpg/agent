package com.hkdzagent.agent.runtime;

public class AgentRunLeaseLostException extends IllegalStateException {

    public AgentRunLeaseLostException(String message) {
        super(message);
    }

    public AgentRunLeaseLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
