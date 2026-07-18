package com.hkdzagent.agent.loop;

public interface AgentLoopModel {

    AgentDecision next(AgentTurn turn);
}
