package com.hkdzagent.agent.loop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

class AgentLoopServiceTest {

    private static final String LOOP_PACKAGE = "com.hkdzagent.agent.loop.";

    @Test
    void completesPlanToolObservationFinalAnswerFlowWithProvidedTraceId() throws Exception {
        Object planner = proxy("AgentPlanner", (method, args) -> {
            if (method.getName().equals("plan")) {
                assertThat(invoke(args[0], "userMessage")).isEqualTo("Get AAPL quote");
                return agentPlan("Fetch quote data before answering");
            }
            return unsupported(method);
        });

        AtomicInteger modelCalls = new AtomicInteger();
        Object model = proxy("AgentLoopModel", (method, args) -> {
            if (!method.getName().equals("next")) {
                return unsupported(method);
            }

            int call = modelCalls.incrementAndGet();
            Object turn = args[0];
            assertThat(invoke(turn, "traceId")).isEqualTo("trace-fixed");
            assertThat(invoke(invoke(turn, "plan"), "summary")).isEqualTo("Fetch quote data before answering");

            if (call == 1) {
                assertThat((List<?>) invoke(turn, "observations")).isEmpty();
                return toolDecision(agentToolCall("httpRequestTool", "{\"url\":\"https://example.com/aapl\"}"));
            }

            List<?> observations = (List<?>) invoke(turn, "observations");
            assertThat(observations).hasSize(1);
            Object observation = observations.get(0);
            assertThat(invoke(observation, "toolName")).isEqualTo("httpRequestTool");
            assertThat(invoke(observation, "content")).isEqualTo("quote-data");
            assertThat(invoke(observation, "success")).isEqualTo(true);
            return finalDecision("AAPL quote summary");
        });

        AtomicReference<String> toolTraceId = new AtomicReference<>();
        Object toolExecutor = proxy("AgentToolExecutor", (method, args) -> {
            if (method.getName().equals("execute")) {
                toolTraceId.set((String) args[0]);
                Object toolCall = args[1];
                assertThat(invoke(toolCall, "name")).isEqualTo("httpRequestTool");
                assertThat(invoke(toolCall, "arguments")).isEqualTo("{\"url\":\"https://example.com/aapl\"}");
                return agentObservation("httpRequestTool", "quote-data", true);
            }
            return unsupported(method);
        });

        Object service = agentLoopService(planner, model, toolExecutor, 3);

        Object result = run(service, agentLoopRequest("Get AAPL quote", "session-1", "trace-fixed"));

        assertThat(invoke(result, "status").toString()).isEqualTo("COMPLETED");
        assertThat(invoke(result, "traceId")).isEqualTo("trace-fixed");
        assertThat(invoke(result, "finalAnswer")).isEqualTo("AAPL quote summary");
        assertThat(toolTraceId.get()).isEqualTo("trace-fixed");

        List<?> steps = (List<?>) invoke(result, "steps");
        assertThat(steps).hasSize(2);
        assertThat(invoke(invoke(steps.get(0), "toolCall"), "name")).isEqualTo("httpRequestTool");
        assertThat(invoke(invoke(steps.get(0), "observation"), "content")).isEqualTo("quote-data");
        assertThat(invoke(steps.get(1), "finalAnswer")).isEqualTo("AAPL quote summary");
    }

    @Test
    void generatesTraceIdWhenRequestDoesNotProvideOne() throws Exception {
        Object planner = proxy("AgentPlanner", (method, args) -> agentPlan("Answer directly"));
        Object model = proxy("AgentLoopModel", (method, args) -> finalDecision("direct answer"));
        Object toolExecutor = proxy("AgentToolExecutor", (method, args) -> {
            fail("Tool executor should not run when the model returns a final answer.");
            return null;
        });
        Object service = agentLoopService(planner, model, toolExecutor, 3);

        Object result = run(service, agentLoopRequest("hello", "session-1", null));

        assertThat(invoke(result, "status").toString()).isEqualTo("COMPLETED");
        assertThat((String) invoke(result, "traceId")).isNotBlank();
        assertThat(invoke(result, "finalAnswer")).isEqualTo("direct answer");
    }

    @Test
    void stopsAtConfiguredMaximumSteps() throws Exception {
        Object planner = proxy("AgentPlanner", (method, args) -> agentPlan("Try bounded work"));
        AtomicInteger toolExecutions = new AtomicInteger();
        Object model = proxy("AgentLoopModel", (method, args) ->
                toolDecision(agentToolCall("httpRequestTool", "{\"url\":\"https://example.com\"}")));
        Object toolExecutor = proxy("AgentToolExecutor", (method, args) -> {
            toolExecutions.incrementAndGet();
            return agentObservation("httpRequestTool", "still incomplete", true);
        });
        Object service = agentLoopService(planner, model, toolExecutor, 2);

        Object result = run(service, agentLoopRequest("loop forever", "session-1", "trace-limit"));

        assertThat(invoke(result, "status").toString()).isEqualTo("STEP_LIMIT_REACHED");
        assertThat(invoke(result, "traceId")).isEqualTo("trace-limit");
        assertThat(((String) invoke(result, "finalAnswer")).toLowerCase()).contains("trace-limit").contains("step");
        assertThat((List<?>) invoke(result, "steps")).hasSize(2);
        assertThat(toolExecutions.get()).isEqualTo(2);
    }

    @Test
    void recordsFailedToolObservationAndAllowsModelToProduceFinalAnswer() throws Exception {
        Object planner = proxy("AgentPlanner", (method, args) -> agentPlan("Use a tool and recover"));
        AtomicInteger modelCalls = new AtomicInteger();
        Object model = proxy("AgentLoopModel", (method, args) -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                return toolDecision(agentToolCall("commandExecuteTool", "{\"command\":\"whoami\"}"));
            }

            List<?> observations = (List<?>) invoke(args[0], "observations");
            assertThat(observations).hasSize(1);
            Object observation = observations.get(0);
            assertThat(invoke(observation, "toolName")).isEqualTo("commandExecuteTool");
            assertThat(invoke(observation, "success")).isEqualTo(false);
            assertThat(((String) invoke(observation, "content")).toLowerCase()).contains("boom");
            return finalDecision("I could not run the command, but here is a safe answer.");
        });
        Object toolExecutor = proxy("AgentToolExecutor", (method, args) -> {
            throw new IllegalStateException("boom");
        });
        Object service = agentLoopService(planner, model, toolExecutor, 3);

        Object result = run(service, agentLoopRequest("run tool", "session-1", "trace-tool-failure"));

        assertThat(invoke(result, "status").toString()).isEqualTo("COMPLETED");
        assertThat(invoke(result, "finalAnswer")).isEqualTo("I could not run the command, but here is a safe answer.");
        List<?> steps = (List<?>) invoke(result, "steps");
        assertThat(invoke(invoke(steps.get(0), "observation"), "success")).isEqualTo(false);
    }

    @Test
    void returnsFailedResultWhenPlannerOrModelFails() throws Exception {
        Object failingPlanner = proxy("AgentPlanner", (method, args) -> {
            throw new IllegalStateException("planner unavailable");
        });
        Object model = proxy("AgentLoopModel", (method, args) -> finalDecision("unused"));
        Object toolExecutor = proxy("AgentToolExecutor", (method, args) -> null);
        Object plannerFailureService = agentLoopService(failingPlanner, model, toolExecutor, 3);

        assertThatCode(() -> run(plannerFailureService,
                agentLoopRequest("hello", "session-1", "trace-planner-failure"))).doesNotThrowAnyException();
        Object plannerFailure = run(plannerFailureService,
                agentLoopRequest("hello", "session-1", "trace-planner-failure"));
        assertThat(invoke(plannerFailure, "status").toString()).isEqualTo("FAILED");
        assertThat(((String) invoke(plannerFailure, "finalAnswer")).toLowerCase())
                .contains("trace-planner-failure")
                .contains("planner unavailable");

        Object planner = proxy("AgentPlanner", (method, args) -> agentPlan("Plan exists"));
        Object failingModel = proxy("AgentLoopModel", (method, args) -> {
            throw new IllegalStateException("model unavailable");
        });
        Object modelFailureService = agentLoopService(planner, failingModel, toolExecutor, 3);

        Object modelFailure = run(modelFailureService,
                agentLoopRequest("hello", "session-1", "trace-model-failure"));
        assertThat(invoke(modelFailure, "status").toString()).isEqualTo("FAILED");
        assertThat(((String) invoke(modelFailure, "finalAnswer")).toLowerCase())
                .contains("trace-model-failure")
                .contains("model unavailable");
    }

    private static Object agentLoopService(Object planner, Object model, Object toolExecutor, int maxSteps) throws Exception {
        Class<?> serviceClass = load("AgentLoopService");
        Constructor<?> constructor = serviceClass.getConstructor(
                load("AgentPlanner"),
                load("AgentLoopModel"),
                load("AgentToolExecutor"),
                int.class
        );
        return constructor.newInstance(planner, model, toolExecutor, maxSteps);
    }

    private static Object run(Object service, Object request) throws Exception {
        return service.getClass().getMethod("run", load("AgentLoopRequest")).invoke(service, request);
    }

    private static Object agentLoopRequest(String userMessage, String sessionId, String traceId) throws Exception {
        return newRecord("AgentLoopRequest",
                new Class<?>[]{String.class, String.class, String.class},
                userMessage, sessionId, traceId);
    }

    private static Object agentPlan(String summary) throws Exception {
        return newRecord("AgentPlan", new Class<?>[]{String.class}, summary);
    }

    private static Object agentToolCall(String name, String arguments) throws Exception {
        return newRecord("AgentToolCall", new Class<?>[]{String.class, String.class}, name, arguments);
    }

    private static Object agentObservation(String toolName, String content, boolean success) throws Exception {
        return newRecord("AgentObservation",
                new Class<?>[]{String.class, String.class, boolean.class},
                toolName, content, success);
    }

    private static Object toolDecision(Object toolCall) throws Exception {
        return load("AgentDecision").getMethod("toolCall", load("AgentToolCall")).invoke(null, toolCall);
    }

    private static Object finalDecision(String answer) throws Exception {
        return load("AgentDecision").getMethod("finalAnswer", String.class).invoke(null, answer);
    }

    private static Object newRecord(String simpleName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Constructor<?> constructor = load(simpleName).getConstructor(parameterTypes);
        return constructor.newInstance(args);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static Object proxy(String interfaceName, ThrowingInvocation invocation) {
        Class<?> interfaceClass = load(interfaceName);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> interfaceName + " proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            return invocation.invoke(method, args == null ? new Object[0] : args);
        };
        return Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class<?>[]{interfaceClass}, handler);
    }

    private static Object unsupported(Method method) {
        throw new AssertionError("Unexpected method call: " + method);
    }

    private static Class<?> load(String simpleName) {
        String className = LOOP_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        Object invoke(Method method, Object[] args) throws Throwable;
    }
}
