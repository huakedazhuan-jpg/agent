package com.hkdzagent.agent.loop;

public record AgentDecision(Type type, AgentToolCall toolCall, String finalAnswer) {

    public enum Type {
        TOOL_CALL,
        FINAL_ANSWER
    }

    public static AgentDecision toolCall(AgentToolCall toolCall) {
        return new AgentDecision(Type.TOOL_CALL, toolCall, null);
    }

    public static AgentDecision finalAnswer(String answer) {
        return new AgentDecision(Type.FINAL_ANSWER, null, answer);
    }
}
