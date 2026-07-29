package com.hkdzagent.agent.context;

public class ContextLimitExceededException extends IllegalArgumentException {

    public ContextLimitExceededException(String message) {
        super(message);
    }
}
