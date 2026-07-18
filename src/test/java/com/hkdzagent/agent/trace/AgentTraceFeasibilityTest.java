package com.hkdzagent.agent.trace;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AgentTraceFeasibilityTest {

    private static final String TRACE_PACKAGE = "com.hkdzagent.agent.trace.";

    @Test
    void recordsConversationModelToolObservationAndFinalAnswerEvents() throws Exception {
        Object repository = newInstance("InMemoryAgentTraceRepository");
        Object sanitizer = newInstance("AgentTraceSanitizer", 80);
        Object recorder = newInstance("AgentTraceRecorder", repository, sanitizer);

        Object trace = invoke(recorder, "startTrace", "trace-1", "session-1", "Get AAPL quote");
        assertThat(invoke(trace, "traceId")).isEqualTo("trace-1");

        invoke(recorder, "recordModelRequest", "trace-1", 1, Map.of(
                "model", "kimi-k2.5",
                "messageCount", 2,
                "toolNames", List.of("httpRequestTool"),
                "Authorization", "Bearer secret-token"
        ));
        invoke(recorder, "recordModelResponse", "trace-1", 1, 200, Duration.ofMillis(123), Map.of(
                "hasToolCalls", true,
                "toolCallNames", List.of("httpRequestTool"),
                "content", "need quote data"
        ));
        invoke(recorder, "recordToolCall", "trace-1", 1, "httpRequestTool",
                "{\"url\":\"https://query1.finance.yahoo.com/v8/finance/chart/AAPL\"}");
        invoke(recorder, "recordToolObservation", "trace-1", 1, "httpRequestTool", true,
                "quote-data", Duration.ofMillis(45));
        invoke(recorder, "recordFinalAnswer", "trace-1", "AAPL quote summary");
        invoke(recorder, "finishTrace", "trace-1", "COMPLETED");

        Object storedTrace = invoke(repository, "findByTraceId", "trace-1");
        assertThat(invoke(storedTrace, "traceId")).isEqualTo("trace-1");
        assertThat(invoke(storedTrace, "sessionId")).isEqualTo("session-1");
        assertThat(invoke(storedTrace, "status").toString()).isEqualTo("COMPLETED");
        assertThat((Long) invoke(storedTrace, "durationMs")).isGreaterThanOrEqualTo(0L);

        List<?> events = (List<?>) invoke(storedTrace, "events");
        assertThat(events).hasSize(5);
        assertThat(eventTypes(events)).containsExactly(
                "MODEL_REQUEST",
                "MODEL_RESPONSE",
                "TOOL_CALL",
                "TOOL_OBSERVATION",
                "FINAL_ANSWER"
        );
        assertThat(invoke(events.get(0), "traceId")).isEqualTo("trace-1");
        assertThat(invoke(events.get(2), "toolName")).isEqualTo("httpRequestTool");
        assertThat(invoke(events.get(3), "success")).isEqualTo(true);
        assertThat(invoke(events.get(4), "contentPreview")).isEqualTo("AAPL quote summary");
    }

    @Test
    void sanitizesSecretsAndTruncatesLongPayloadsBeforeStoringEvents() throws Exception {
        Object repository = newInstance("InMemoryAgentTraceRepository");
        Object sanitizer = newInstance("AgentTraceSanitizer", 24);
        Object recorder = newInstance("AgentTraceRecorder", repository, sanitizer);

        invoke(recorder, "startTrace", "trace-secure", "session-secure", "user message with api-key=abc123");
        invoke(recorder, "recordModelRequest", "trace-secure", 1, Map.of(
                "Authorization", "Bearer secret-token",
                "api_key", "secret-api-key",
                "prompt", "012345678901234567890123456789"
        ));
        invoke(recorder, "recordToolCall", "trace-secure", 1, "commandExecuteTool",
                "echo hello && set MOONSHOT_API_KEY=secret");
        invoke(recorder, "finishTrace", "trace-secure", "FAILED");

        Object storedTrace = invoke(repository, "findByTraceId", "trace-secure");
        String serialized = storedTrace.toString().toLowerCase();

        assertThat(serialized).doesNotContain("secret-token", "secret-api-key", "moonshot_api_key=secret");
        assertThat(serialized).contains("[redacted]");

        List<?> events = (List<?>) invoke(storedTrace, "events");
        String modelPayloadPreview = String.valueOf(invoke(events.get(0), "metadata"));
        String toolArgumentsPreview = String.valueOf(invoke(events.get(1), "argumentsPreview"));
        assertThat(modelPayloadPreview.length()).isLessThan(200);
        assertThat(toolArgumentsPreview.length()).isLessThanOrEqualTo(36);
    }

    @Test
    void recordsErrorsAndStepLimitAsTraceTerminalState() throws Exception {
        Object repository = newInstance("InMemoryAgentTraceRepository");
        Object sanitizer = newInstance("AgentTraceSanitizer", 80);
        Object recorder = newInstance("AgentTraceRecorder", repository, sanitizer);

        invoke(recorder, "startTrace", "trace-error", "session-error", "question");
        invoke(recorder, "recordError", "trace-error", 2, "model unavailable", Duration.ofMillis(9));
        invoke(recorder, "finishTrace", "trace-error", "FAILED");

        Object failedTrace = invoke(repository, "findByTraceId", "trace-error");
        assertThat(invoke(failedTrace, "status").toString()).isEqualTo("FAILED");
        List<?> failedEvents = (List<?>) invoke(failedTrace, "events");
        assertThat(eventTypes(failedEvents)).containsExactly("ERROR");
        assertThat(invoke(failedEvents.get(0), "errorMessage")).isEqualTo("model unavailable");
        assertThat(invoke(failedEvents.get(0), "durationMs")).isEqualTo(9L);

        invoke(recorder, "startTrace", "trace-limit", "session-limit", "question");
        invoke(recorder, "recordStepLimit", "trace-limit", 5, "agent tool step limit reached");
        invoke(recorder, "finishTrace", "trace-limit", "STEP_LIMIT_REACHED");

        Object limitedTrace = invoke(repository, "findByTraceId", "trace-limit");
        assertThat(invoke(limitedTrace, "status").toString()).isEqualTo("STEP_LIMIT_REACHED");
        List<?> limitedEvents = (List<?>) invoke(limitedTrace, "events");
        assertThat(eventTypes(limitedEvents)).containsExactly("STEP_LIMIT");
        assertThat(invoke(limitedEvents.get(0), "step")).isEqualTo(5);
    }

    @Test
    void repositoryReturnsRecentTracesInReverseStartOrder() throws Exception {
        Object repository = newInstance("InMemoryAgentTraceRepository");
        Object sanitizer = newInstance("AgentTraceSanitizer", 80);
        Object recorder = newInstance("AgentTraceRecorder", repository, sanitizer);

        invoke(recorder, "startTrace", "trace-old", "session-1", "old");
        invoke(recorder, "startTrace", "trace-new", "session-1", "new");

        List<?> recent = (List<?>) invoke(repository, "findRecent", 2);

        assertThat(recent).hasSize(2);
        assertThat(invoke(recent.get(0), "traceId")).isEqualTo("trace-new");
        assertThat(invoke(recent.get(1), "traceId")).isEqualTo("trace-old");
    }

    private static List<String> eventTypes(List<?> events) {
        return events.stream()
                .map(event -> {
                    try {
                        return invoke(event, "type").toString();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private static Object newInstance(String simpleName, Object... args) throws Exception {
        Class<?> targetClass = load(simpleName);
        for (Constructor<?> constructor : targetClass.getConstructors()) {
            if (constructor.getParameterCount() == args.length) {
                return constructor.newInstance(args);
            }
        }
        fail("Expected " + targetClass.getName() + " to have a public constructor with "
                + args.length + " argument(s).");
        return null;
    }

    private static Object invoke(Object target, String methodName, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                return method.invoke(target, args);
            }
        }
        fail("Expected " + target.getClass().getName() + " to expose method " + methodName
                + " with " + args.length + " argument(s).");
        return null;
    }

    private static Class<?> load(String simpleName) {
        String className = TRACE_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }
}
