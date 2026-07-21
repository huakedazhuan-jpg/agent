package com.hkdzagent.agent.tool;

/**
 * Stable contract implemented by every tool that can be exposed to an agent.
 * Policy enforcement is deliberately handled by the execution pipeline rather
 * than by callers invoking a tool directly.
 */
public interface AgentTool<I, O> {

    ToolMetadata metadata();

    Class<I> inputType();

    O execute(I input) throws Exception;
}
