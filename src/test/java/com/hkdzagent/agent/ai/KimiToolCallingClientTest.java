package com.hkdzagent.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.tool.ToolRegistryConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class KimiToolCallingClientTest {

    private static final String CLASS_NAME = "com.hkdzagent.agent.ai.KimiToolCallingClient";
    private static final Path KIMI_CLIENT_SOURCE =
            Path.of("src/main/java/com/hkdzagent/agent/ai/KimiToolCallingClient.java");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void askWithToolsUsesAgentLoopServiceForLoopControl() throws Exception {
        String source = Files.readString(KIMI_CLIENT_SOURCE);
        String methodSource = askWithToolsSource(source);

        assertThat(source).contains("AgentLoopService", "AgentLoopRequest", "AgentLoopResult");
        assertThat(methodSource).contains(".run(new AgentLoopRequest");
        assertThat(methodSource).doesNotContain("while (true)");
    }

    @Test
    void sendsThinkingAndPreservesReasoningContentWhenContinuingAfterToolCall() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        AtomicReference<String> requestedUrl = new AtomicReference<>();

        try (MockOpenAiServer server = MockOpenAiServer.start(requestBodies, requestIndex -> {
            if (requestIndex == 1) {
                return toolCallResponse(
                        "need current quote data",
                        "httpRequestTool",
                        "{\"url\":\"https://query1.finance.yahoo.com/v8/finance/chart/AAPL\"}"
                );
            }
            return finalResponse("AAPL quote summary");
        })) {
            Object client = newClient(
                    server.baseUrl(),
                    new TestChatMemory(),
                    5,
                    request -> "file tool should not be called",
                    request -> "command tool should not be called",
                    request -> {
                        requestedUrl.set(request.url());
                        return "quote-data";
                    }
            );

            String answer = askWithTools(client, "Get AAPL quote", "session-1");

            assertThat(answer).isEqualTo("AAPL quote summary");
            assertThat(requestedUrl.get()).isEqualTo("https://query1.finance.yahoo.com/v8/finance/chart/AAPL");
            assertThat(requestBodies).hasSize(2);

            JsonNode firstRequest = readRequest(requestBodies, 0);
            assertThat(firstRequest.path("model").asText()).isEqualTo("kimi-k2.5");
            assertThat(firstRequest.path("temperature").asDouble()).isEqualTo(1.0);
            assertThat(firstRequest.path("max_tokens").asInt()).isEqualTo(16000);
            assertThat(firstRequest.path("thinking").path("type").asText()).isEqualTo("enabled");
            assertThat(toolNames(firstRequest))
                    .containsExactlyInAnyOrder("fileOperationTool", "commandExecuteTool", "httpRequestTool",
                            "knowledgeSearchTool");

            JsonNode secondRequest = readRequest(requestBodies, 1);
            JsonNode assistantToolCallMessage = firstAssistantToolCallMessage(secondRequest);
            assertThat(assistantToolCallMessage.path("reasoning_content").asText())
                    .isEqualTo("need current quote data");
            assertThat(assistantToolCallMessage.path("tool_calls").get(0).path("id").asText())
                    .isEqualTo("call_1");
            assertThat(assistantToolCallMessage.path("tool_calls").get(0).path("function").path("name").asText())
                    .isEqualTo("httpRequestTool");

            JsonNode toolMessage = firstToolMessage(secondRequest);
            assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call_1");
            assertThat(toolMessage.path("content").asText()).contains("quote-data");
        }
    }

    @Test
    void returnsControlledToolMessageForUnknownToolWithoutExecutingLocalTools() throws Exception {
        List<String> requestBodies = new ArrayList<>();

        try (MockOpenAiServer server = MockOpenAiServer.start(requestBodies, requestIndex -> {
            if (requestIndex == 1) {
                return toolCallResponse("need unsupported action", "unknownTool", "{\"input\":\"abc\"}");
            }
            return finalResponse("unsupported tool handled");
        })) {
            Object client = newClient(
                    server.baseUrl(),
                    new TestChatMemory(),
                    5,
                    request -> {
                        fail("file tool must not be called for unknown tool");
                        return "";
                    },
                    request -> {
                        fail("command tool must not be called for unknown tool");
                        return "";
                    },
                    request -> {
                        fail("http tool must not be called for unknown tool");
                        return "";
                    }
            );

            String answer = askWithTools(client, "Use an unknown tool", "session-unknown");

            assertThat(answer).isEqualTo("unsupported tool handled");
            assertThat(requestBodies).hasSize(2);

            JsonNode toolMessage = firstToolMessage(readRequest(requestBodies, 1));
            assertThat(toolMessage.path("content").asText().toLowerCase(Locale.ROOT))
                    .contains("unknown tool");
        }
    }

    @Test
    void stopsWhenToolRoundLimitIsReached() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        AtomicInteger toolCalls = new AtomicInteger();

        try (MockOpenAiServer server = MockOpenAiServer.start(requestBodies,
                requestIndex -> toolCallResponse(
                        "still need more data",
                        "httpRequestTool",
                        "{\"url\":\"https://query1.finance.yahoo.com/v8/finance/chart/MSFT\"}"
                ))) {
            Object client = newClient(
                    server.baseUrl(),
                    new TestChatMemory(),
                    1,
                    request -> "file tool should not be called",
                    request -> "command tool should not be called",
                    request -> {
                        toolCalls.incrementAndGet();
                        return "quote-data";
                    }
            );

            String answer = askWithTools(client, "Get MSFT quote", "session-limit");

            assertThat(answer.toLowerCase(Locale.ROOT)).contains("tool").contains("step").contains("limit");
            assertThat(answer).contains("session-limit");
            assertThat(toolCalls.get()).isEqualTo(1);
            assertThat(requestBodies.size()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void keepsConversationHistorySeparatedBySession() throws Exception {
        List<String> requestBodies = new ArrayList<>();

        try (MockOpenAiServer server = MockOpenAiServer.start(requestBodies,
                requestIndex -> finalResponse("answer " + requestIndex))) {
            ChatMemory chatMemory = new TestChatMemory();
            Object client = newClient(
                    server.baseUrl(),
                    chatMemory,
                    5,
                    request -> "file tool should not be called",
                    request -> "command tool should not be called",
                    request -> "http tool should not be called"
            );

            askWithTools(client, "first question", "same-session");
            askWithTools(client, "second question", "same-session");
            askWithTools(client, "fresh question", "other-session");

            JsonNode secondRequest = readRequest(requestBodies, 1);
            assertThat(messageContents(secondRequest))
                    .contains("first question", "answer 1", "second question");

            JsonNode otherSessionRequest = readRequest(requestBodies, 2);
            assertThat(messageContents(otherSessionRequest))
                    .contains("fresh question")
                    .doesNotContain("first question", "answer 1");
        }
    }

    private Object newClient(
            String baseUrl,
            ChatMemory chatMemory,
            int maxToolRounds,
            Function<ToolRegistryConfig.FileRequest, String> fileOperationTool,
            Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool,
            Function<ToolRegistryConfig.WebRequest, String> httpRequestTool
    ) throws Exception {
        Class<?> clientClass = loadClientClass();
        Constructor<?> constructor = clientClass.getConstructor(
                ObjectMapper.class,
                ChatMemory.class,
                String.class,
                String.class,
                String.class,
                double.class,
                int.class,
                Duration.class,
                int.class,
                Function.class,
                Function.class,
                Function.class
        );
        return constructor.newInstance(
                objectMapper,
                chatMemory,
                "test-api-key",
                baseUrl,
                "kimi-k2.5",
                1.0,
                16000,
                Duration.ofSeconds(5),
                maxToolRounds,
                fileOperationTool,
                commandExecuteTool,
                httpRequestTool
        );
    }

    private String askWithTools(Object client, String userMessage, String sessionId) throws Exception {
        Method method = client.getClass().getMethod("askWithTools", String.class, String.class);
        return (String) method.invoke(client, userMessage, sessionId);
    }

    private Class<?> loadClientClass() {
        try {
            return Class.forName(CLASS_NAME);
        } catch (ClassNotFoundException e) {
            fail("Expected " + CLASS_NAME + " to exist as the custom Kimi tool-calling client.");
            return Object.class;
        }
    }

    private static String askWithToolsSource(String source) {
        int methodStart = source.indexOf("String askWithTools");
        assertThat(methodStart).isGreaterThanOrEqualTo(0);

        int methodEnd = source.indexOf("\n    }", methodStart);
        assertThat(methodEnd).isGreaterThan(methodStart);

        return source.substring(methodStart, methodEnd);
    }

    private JsonNode readRequest(List<String> requestBodies, int index) throws IOException {
        return objectMapper.readTree(requestBodies.get(index));
    }

    private static List<String> toolNames(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
        return names;
    }

    private static JsonNode firstAssistantToolCallMessage(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if ("assistant".equals(message.path("role").asText()) && message.has("tool_calls")) {
                return message;
            }
        }
        fail("Expected request to contain an assistant message with tool_calls.");
        return null;
    }

    private static JsonNode firstToolMessage(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())) {
                return message;
            }
        }
        fail("Expected request to contain a tool result message.");
        return null;
    }

    private static List<String> messageContents(JsonNode request) {
        List<String> contents = new ArrayList<>();
        request.path("messages").forEach(message -> contents.add(message.path("content").asText()));
        return contents;
    }

    private static String toolCallResponse(String reasoningContent, String toolName, String argumentsJson) {
        return """
                {
                  "id": "chatcmpl-tool",
                  "object": "chat.completion",
                  "created": 0,
                  "model": "kimi-k2.5",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "",
                        "reasoning_content": "%s",
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "%s",
                              "arguments": "%s"
                            }
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """.formatted(escapeJson(reasoningContent), escapeJson(toolName), escapeJson(argumentsJson));
    }

    private static String finalResponse(String answer) {
        return """
                {
                  "id": "chatcmpl-final",
                  "object": "chat.completion",
                  "created": 0,
                  "model": "kimi-k2.5",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """.formatted(escapeJson(answer));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface ResponseScript {
        String responseFor(int requestIndex);
    }

    private static final class MockOpenAiServer implements AutoCloseable {

        private final HttpServer server;

        private MockOpenAiServer(HttpServer server) {
            this.server = server;
        }

        static MockOpenAiServer start(List<String> requestBodies, ResponseScript responseScript) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/chat/completions",
                    exchange -> handleCompletion(exchange, requestBodies, responseScript));
            server.start();
            return new MockOpenAiServer(server);
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void handleCompletion(
                HttpExchange exchange,
                List<String> requestBodies,
                ResponseScript responseScript
        ) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            writeJson(exchange, responseScript.responseFor(requestBodies.size()));
        }

        private static void writeJson(HttpExchange exchange, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }

    private static final class TestChatMemory implements ChatMemory {

        private final Map<String, List<Message>> messagesByConversation = new ConcurrentHashMap<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            messagesByConversation.compute(conversationId, (key, existingMessages) -> {
                List<Message> updatedMessages = new ArrayList<>();
                if (existingMessages != null) {
                    updatedMessages.addAll(existingMessages);
                }
                updatedMessages.addAll(messages);
                return updatedMessages;
            });
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.copyOf(messagesByConversation.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void clear(String conversationId) {
            messagesByConversation.remove(conversationId);
        }
    }
}
