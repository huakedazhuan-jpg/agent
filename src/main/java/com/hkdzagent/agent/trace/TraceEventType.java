package com.hkdzagent.agent.trace;

public enum TraceEventType {
    MODEL_REQUEST,
    MODEL_RESPONSE,
    TOOL_CALL,
    TOOL_OBSERVATION,
    FINAL_ANSWER,
    ERROR,
    STEP_LIMIT
}
