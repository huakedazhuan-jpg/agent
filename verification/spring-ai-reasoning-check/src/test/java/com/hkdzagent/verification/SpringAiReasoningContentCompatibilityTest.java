package com.hkdzagent.verification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiReasoningContentCompatibilityTest {

    @Test
    void springAi114PreservesReasoningContentWhenSendingSecondToolCallRequest() throws Exception {
        List<String> requestBodies = new ArrayList<>();

        try (MockOpenAiServer server = MockOpenAiServer.start(requestBodies)) {
            ToolCallback echoTool = FunctionToolCallback
                    .builder("echoTool", (EchoRequest request) -> "tool-result:" + request.input())
                    .description("Echoes the input.")
                    .inputType(EchoRequest.class)
                    .build();

            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(server.baseUrl())
                    .apiKey("test-key")
                    .build();

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model("kimi-k2.5")
                    .temperature(1.0)
                    .maxTokens(16000)
                    .toolChoice("auto")
                    .toolCallbacks(echoTool)
                    .internalToolExecutionEnabled(true)
                    .extraBody(Map.of("thinking", Map.of("type", "enabled")))
                    .build();

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(options)
                    .toolCallingManager(DefaultToolCallingManager.builder().build())
                    .build();

            ChatResponse response = chatModel.call(new Prompt("Call echoTool once."));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("final answer");
            assertThat(requestBodies).hasSize(2);
            assertThat(requestBodies.get(1)).contains("\"reasoning_content\":\"need to call echoTool\"");
        }
    }

    record EchoRequest(String input) {
    }

    private static final class MockOpenAiServer implements AutoCloseable {

        private final HttpServer server;

        private MockOpenAiServer(HttpServer server) {
            this.server = server;
        }

        static MockOpenAiServer start(List<String> requestBodies) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/chat/completions", exchange -> handleCompletion(exchange, requestBodies));
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

        private static void handleCompletion(HttpExchange exchange, List<String> requestBodies) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);

            if (requestBodies.size() == 1) {
                writeJson(exchange, """
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
                                "reasoning_content": "need to call echoTool",
                                "tool_calls": [
                                  {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {
                                      "name": "echoTool",
                                      "arguments": "{\\"input\\":\\"abc\\"}"
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
                        """);
            } else {
                writeJson(exchange, """
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
                                "content": "final answer"
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
                        """);
            }
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
}
