package com.hkdzagent.agent.loop;

public record AgentStep(
        AgentPlan plan,
        AgentToolCall toolCall,
        AgentObservation observation,
        String finalAnswer
) {

    public static AgentStep toolStep(AgentPlan plan, AgentToolCall toolCall, AgentObservation observation) {
        return new AgentStep(plan, toolCall, observation, null);
    }

    public static AgentStep finalStep(AgentPlan plan, String finalAnswer) {
        return new AgentStep(plan, null, null, finalAnswer);
    }
}
