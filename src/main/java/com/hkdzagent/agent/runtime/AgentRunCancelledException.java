package com.hkdzagent.agent.runtime;

public class AgentRunCancelledException extends IllegalStateException {

    public AgentRunCancelledException(String message) {
        super(message);
    }
}
