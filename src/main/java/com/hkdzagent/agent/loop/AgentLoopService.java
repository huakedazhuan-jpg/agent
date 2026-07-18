package com.hkdzagent.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AgentLoopService {

    private final AgentPlanner planner;
    private final AgentLoopModel model;
    private final AgentToolExecutor toolExecutor;
    private final int maxSteps;

    public AgentLoopService(
            AgentPlanner planner,
            AgentLoopModel model,
            AgentToolExecutor toolExecutor,
            int maxSteps
    ) {
        this.planner = planner;
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public AgentLoopResult run(AgentLoopRequest request) {
        String traceId = traceId(request);
        List<AgentStep> steps = new ArrayList<>();
        List<AgentObservation> observations = new ArrayList<>();

        try {
            AgentPlan plan = planner.plan(request);
            for (int step = 1; step <= maxSteps; step++) {
                AgentDecision decision = model.next(new AgentTurn(traceId, request, plan, List.copyOf(observations), step));
                if (decision.type() == AgentDecision.Type.FINAL_ANSWER) {
                    steps.add(AgentStep.finalStep(plan, decision.finalAnswer()));
                    return new AgentLoopResult(
                            AgentLoopResult.Status.COMPLETED,
                            traceId,
                            decision.finalAnswer(),
                            List.copyOf(steps)
                    );
                }

                AgentToolCall toolCall = decision.toolCall();
                AgentObservation observation = executeTool(traceId, toolCall);
                observations.add(observation);
                steps.add(AgentStep.toolStep(plan, toolCall, observation));
            }

            return new AgentLoopResult(
                    AgentLoopResult.Status.STEP_LIMIT_REACHED,
                    traceId,
                    "agent tool step limit reached for traceId " + traceId,
                    List.copyOf(steps)
            );
        } catch (Exception e) {
            return failed(traceId, e, steps);
        }
    }

    private AgentObservation executeTool(String traceId, AgentToolCall toolCall) {
        try {
            AgentObservation observation = toolExecutor.execute(traceId, toolCall);
            if (observation == null) {
                return new AgentObservation(toolCall.name(), "tool returned no observation", false);
            }
            return observation;
        } catch (Exception e) {
            return new AgentObservation(toolCall.name(), "tool execution failed: " + e.getMessage(), false);
        }
    }

    private AgentLoopResult failed(String traceId, Exception e, List<AgentStep> steps) {
        return new AgentLoopResult(
                AgentLoopResult.Status.FAILED,
                traceId,
                "agent failed for traceId " + traceId + ": " + e.getMessage(),
                List.copyOf(steps)
        );
    }

    private String traceId(AgentLoopRequest request) {
        if (request.traceId() != null && !request.traceId().isBlank()) {
            return request.traceId();
        }
        return UUID.randomUUID().toString();
    }
}
