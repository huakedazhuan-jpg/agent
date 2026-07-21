package com.hkdzagent.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hkdzagent.agent.loop.AgentDecision;
import com.hkdzagent.agent.loop.AgentLoopRequest;
import com.hkdzagent.agent.loop.AgentLoopResult;
import com.hkdzagent.agent.loop.AgentLoopPausedException;
import com.hkdzagent.agent.loop.AgentLoopService;
import com.hkdzagent.agent.loop.AgentObservation;
import com.hkdzagent.agent.loop.AgentPlan;
import com.hkdzagent.agent.loop.AgentToolCall;
import com.hkdzagent.agent.loop.AgentTurn;
import com.hkdzagent.agent.tool.ToolRegistryConfig;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Service
public class KimiToolCallingClient {

    private static final String SYSTEM_PROMPT = """
            你是一个股票信息查询 agent，负责帮助用户查询股票、ETF、指数和上市公司公开信息。
            优先使用 HTTP 工具查询公开行情数据，常用数据源包括 Yahoo Finance、Stooq 和常规搜索网站。
            可使用 query1.finance.yahoo.com、query2.finance.yahoo.com 查询行情摘要或图表数据，可使用 stooq.com 查询补充行情。
            回答时要注明数据来源和查询时间，说明行情数据可能延迟或缺失。
            只做信息查询、整理和解释，不提供投资建议，不承诺收益，不替用户做买卖决策。
            """;

    private final ObjectMapper objectMapper;
    private final ChatMemory chatMemory;
    private final String apiKey;
    private final URI completionsUri;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration requestTimeout;
    private final int maxToolRounds;
    private final int historyLimit;
    private final Function<ToolRegistryConfig.FileRequest, String> fileOperationTool;
    private final Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool;
    private final Function<ToolRegistryConfig.WebRequest, String> httpRequestTool;
    private final Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool;

    @Autowired
    public KimiToolCallingClient(
            ObjectMapper objectMapper,
            ChatMemory chatMemory,
            OpenAiCompatibleProperties modelProperties,
            KimiProperties kimiProperties,
            @Qualifier("fileOperationTool") Function<ToolRegistryConfig.FileRequest, String> fileOperationTool,
            @Qualifier("commandExecuteTool") Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool,
            @Qualifier("httpRequestTool") Function<ToolRegistryConfig.WebRequest, String> httpRequestTool,
            @Qualifier("knowledgeSearchTool") Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool
    ) {
        this(objectMapper, chatMemory, modelProperties.apiKey(), modelProperties.completionsUri(),
                modelProperties.model(), modelProperties.temperature(), modelProperties.maxTokens(),
                kimiProperties.requestTimeout(), kimiProperties.maxToolRounds(), kimiProperties.historyLimit(),
                fileOperationTool, commandExecuteTool, httpRequestTool, knowledgeSearchTool);
    }

    public KimiToolCallingClient(
            ObjectMapper objectMapper,
            ChatMemory chatMemory,
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            int maxTokens,
            Duration requestTimeout,
            int maxToolRounds,
            @Qualifier("fileOperationTool") Function<ToolRegistryConfig.FileRequest, String> fileOperationTool,
            @Qualifier("commandExecuteTool") Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool,
            @Qualifier("httpRequestTool") Function<ToolRegistryConfig.WebRequest, String> httpRequestTool,
            @Qualifier("knowledgeSearchTool") Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool
    ) {
        this(objectMapper, chatMemory, apiKey, completionsUri(baseUrl), model, temperature, maxTokens, requestTimeout,
                maxToolRounds, 20, fileOperationTool, commandExecuteTool, httpRequestTool, knowledgeSearchTool);
    }

    public KimiToolCallingClient(
            ObjectMapper objectMapper,
            ChatMemory chatMemory,
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            int maxTokens,
            Duration requestTimeout,
            int maxToolRounds,
            @Qualifier("fileOperationTool") Function<ToolRegistryConfig.FileRequest, String> fileOperationTool,
            @Qualifier("commandExecuteTool") Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool,
            @Qualifier("httpRequestTool") Function<ToolRegistryConfig.WebRequest, String> httpRequestTool
    ) {
        this(objectMapper, chatMemory, apiKey, completionsUri(baseUrl), model, temperature, maxTokens, requestTimeout,
                maxToolRounds, 20, fileOperationTool, commandExecuteTool, httpRequestTool,
                request -> "knowledge search unavailable: no local RAG knowledge base is configured");
    }

    private KimiToolCallingClient(
            ObjectMapper objectMapper,
            ChatMemory chatMemory,
            String apiKey,
            URI completionsUri,
            String model,
            double temperature,
            int maxTokens,
            Duration requestTimeout,
            int maxToolRounds,
            int historyLimit,
            Function<ToolRegistryConfig.FileRequest, String> fileOperationTool,
            Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool,
            Function<ToolRegistryConfig.WebRequest, String> httpRequestTool,
            Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool
    ) {
        this.objectMapper = objectMapper;
        this.chatMemory = chatMemory;
        this.apiKey = apiKey;
        this.completionsUri = completionsUri;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.requestTimeout = requestTimeout;
        this.maxToolRounds = maxToolRounds;
        this.historyLimit = historyLimit;
        this.fileOperationTool = fileOperationTool;
        this.commandExecuteTool = commandExecuteTool;
        this.httpRequestTool = httpRequestTool;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    public String askWithTools(String userMessage, String sessionId) {
        return askWithTools(userMessage, sessionId, sessionId, AgentExecutionObserver.NOOP);
    }

    public String askWithTools(
            String userMessage,
            String sessionId,
            String traceId,
            AgentExecutionObserver observer
    ) {
        return runWithTools(userMessage, sessionId, traceId, observer).finalAnswer();
    }

    public AgentLoopResult runWithTools(
            String userMessage,
            String sessionId,
            String traceId,
            AgentExecutionObserver observer
    ) {
        String conversationId = normalizeSessionId(sessionId);
        List<ObjectNode> messages = requestMessages(conversationId, userMessage);
        AgentExecutionObserver safeObserver = observer == null ? AgentExecutionObserver.NOOP : observer;
        AgentLoopService agentLoopService = agentLoopService(messages, userMessage, conversationId, safeObserver);
        return agentLoopService.run(new AgentLoopRequest(userMessage, conversationId, traceId));
    }

    public AgentLoopResult resumeWithApprovedTool(
            String checkpointJson,
            AgentObservation approvedObservation,
            AgentExecutionObserver observer
    ) {
        try {
            JsonNode checkpoint = objectMapper.readTree(checkpointJson);
            String userMessage = checkpoint.path("userMessage").asText();
            String conversationId = checkpoint.path("conversationId").asText();
            String traceId = checkpoint.path("traceId").asText();
            int approvedStep = checkpoint.path("step").asInt();
            List<ObjectNode> messages = new ArrayList<>();
            checkpoint.path("messages").forEach(message -> messages.add((ObjectNode) message.deepCopy()));
            JsonNode assistantMessage = checkpoint.path("assistantMessage");
            JsonNode toolCall = checkpoint.path("toolCall");
            String toolName = toolCall.path("function").path("name").asText();
            String arguments = toolCall.path("function").path("arguments").asText("{}");
            AgentExecutionObserver safeObserver = observer == null ? AgentExecutionObserver.NOOP : observer;
            if (approvedObservation == null
                    || !toolName.equals(approvedObservation.toolName())) {
                throw new SecurityException(
                        "approved tool observation does not match checkpoint tool call");
            }

            safeObserver.toolStarted(approvedStep, toolName);
            safeObserver.toolCompleted(
                    approvedStep, toolName,
                    approvedObservation.success(), approvedObservation.content());
            messages.add(assistantMessageForNextRequest(assistantMessage));
            messages.add(toolResultMessage(toolCall, approvedObservation.content()));

            AgentLoopService loop = agentLoopService(
                    messages, userMessage, conversationId, safeObserver);
            return loop.runFrom(
                    new AgentLoopRequest(userMessage, conversationId, traceId), approvedStep + 1);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid agent approval checkpoint", exception);
        }
    }

    private AgentLoopService agentLoopService(
            List<ObjectNode> messages,
            String userMessage,
            String conversationId,
            AgentExecutionObserver observer
    ) {
        AtomicReference<JsonNode> pendingAssistantMessage = new AtomicReference<>();
        AtomicReference<JsonNode> pendingToolCall = new AtomicReference<>();
        AtomicInteger addedObservationCount = new AtomicInteger();

        return new AgentLoopService(
                request -> new AgentPlan("Use available tools when needed, observe results, then answer."),
                turn -> nextDecision(messages, userMessage, conversationId, pendingAssistantMessage, pendingToolCall,
                        addedObservationCount, observer, turn),
                (traceId, toolCall) -> {
                    int step = addedObservationCount.get() + 1;
                    String toolCallId = pendingToolCall.get() == null
                            ? ""
                            : pendingToolCall.get().path("id").asText();
                    observer.toolStarted(step, toolCall.name());
                    AgentObservation observation = observer.executeTool(
                            step, toolCallId, toolCall.name(), toolCall.arguments());
                    if (observation == null) {
                        observation = executeAgentTool(toolCall);
                    }
                    observer.toolCompleted(
                            step,
                            toolCall.name(),
                            observation.success(),
                            observation.content()
                    );
                    return observation;
                },
                maxToolRounds
        );
    }

    private AgentDecision nextDecision(
            List<ObjectNode> messages,
            String userMessage,
            String conversationId,
            AtomicReference<JsonNode> pendingAssistantMessage,
            AtomicReference<JsonNode> pendingToolCall,
            AtomicInteger addedObservationCount,
            AgentExecutionObserver observer,
            AgentTurn turn
    ) {
        appendObservationMessages(messages, pendingAssistantMessage, pendingToolCall, addedObservationCount,
                turn.observations());

        observer.modelStarted(turn.step());
        JsonNode assistantMessage = callModel(messages, observer, turn.step());
        observer.modelCompleted(turn.step());
        JsonNode toolCalls = assistantMessage.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            String answer = assistantMessage.path("content").asText("");
            chatMemory.add(conversationId, List.of(new UserMessage(userMessage), new AssistantMessage(answer)));
            return AgentDecision.finalAnswer(answer);
        }

        JsonNode toolCall = toolCalls.get(0);
        pendingAssistantMessage.set(assistantMessage);
        pendingToolCall.set(toolCall);
        String toolName = toolCall.path("function").path("name").asText();
        String arguments = toolCall.path("function").path("arguments").asText("{}");
        String toolCallId = toolCall.path("id").asText();
        observer.toolCallRequested(turn.step(), toolCallId, toolName, arguments);
        if (observer.requiresApproval(turn.step(), toolCallId, toolName, arguments)) {
            String checkpoint = approvalCheckpoint(
                    messages,
                    userMessage,
                    conversationId,
                    turn.traceId(),
                    turn.step(),
                    assistantMessage,
                    toolCall
            );
            observer.approvalRequired(turn.step(), toolCallId, toolName, arguments, checkpoint);
            throw new AgentLoopPausedException("agent is waiting for tool approval");
        }
        return AgentDecision.toolCall(new AgentToolCall(toolName, arguments));
    }

    private String approvalCheckpoint(
            List<ObjectNode> messages,
            String userMessage,
            String conversationId,
            String traceId,
            int step,
            JsonNode assistantMessage,
            JsonNode toolCall
    ) {
        ObjectNode checkpoint = objectMapper.createObjectNode();
        checkpoint.put("schemaVersion", 1);
        checkpoint.put("userMessage", userMessage);
        checkpoint.put("conversationId", conversationId);
        checkpoint.put("traceId", traceId);
        checkpoint.put("step", step);
        checkpoint.set("messages", messagesArray(messages));
        checkpoint.set("assistantMessage", assistantMessage.deepCopy());
        checkpoint.set("toolCall", toolCall.deepCopy());
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to serialize agent approval checkpoint", exception);
        }
    }

    private void appendObservationMessages(
            List<ObjectNode> messages,
            AtomicReference<JsonNode> pendingAssistantMessage,
            AtomicReference<JsonNode> pendingToolCall,
            AtomicInteger addedObservationCount,
            List<AgentObservation> observations
    ) {
        while (addedObservationCount.get() < observations.size()) {
            AgentObservation observation = observations.get(addedObservationCount.get());
            JsonNode assistantMessage = pendingAssistantMessage.get();
            JsonNode toolCall = pendingToolCall.get();
            if (assistantMessage != null && toolCall != null) {
                messages.add(assistantMessageForNextRequest(assistantMessage));
                messages.add(toolResultMessage(toolCall, observation.content()));
                pendingAssistantMessage.set(null);
                pendingToolCall.set(null);
            }
            addedObservationCount.incrementAndGet();
        }
    }

    private AgentObservation executeAgentTool(AgentToolCall toolCall) {
        String result = executeTool(toolCall.name(), toolCall.arguments());
        return new AgentObservation(toolCall.name(), result, toolSucceeded(result));
    }

    private boolean toolSucceeded(String result) {
        String normalized = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return !normalized.contains("failed")
                && !normalized.contains("rejected")
                && !normalized.contains("unknown tool");
    }

    private List<ObjectNode> requestMessages(String conversationId, String userMessage) {
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(message("system", SYSTEM_PROMPT));

        for (Message message : lastMessages(chatMemory.get(conversationId), historyLimit)) {
            ObjectNode historyMessage = historyMessage(message);
            if (historyMessage != null) {
                messages.add(historyMessage);
            }
        }

        messages.add(message("user", userMessage));
        return messages;
    }

    private ObjectNode historyMessage(Message message) {
        if (message.getMessageType() == MessageType.USER) {
            return message("user", message.getText());
        }
        if (message.getMessageType() == MessageType.ASSISTANT) {
            return message("assistant", message.getText());
        }
        return null;
    }

    private List<Message> lastMessages(List<Message> messages, int limit) {
        int safeLimit = Math.max(0, limit);
        int fromIndex = Math.max(0, messages.size() - safeLimit);
        return messages.subList(fromIndex, messages.size());
    }

    private JsonNode callModel(
            List<ObjectNode> messages,
            AgentExecutionObserver observer,
            int step
    ) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", model);
            request.set("messages", messagesArray(messages));
            request.put("temperature", temperature);
            request.put("max_tokens", maxTokens);
            request.put("stream", true);
            request.set("thinking", thinking());
            request.set("tools", toolDefinitions());
            request.put("tool_choice", "auto");

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(requestTimeout)
                    .build();
            HttpRequest httpRequest = HttpRequest.newBuilder(completionsUri)
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Kimi request failed with status " + response.statusCode()
                        + ": " + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                return readStreamingMessage(response.body(), observer, step);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").get(0).path("message");
            String content = message.path("content").asText("");
            if (!content.isEmpty()) {
                observer.tokenDelta(step, content);
            }
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kimi request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Kimi request failed: " + e.getMessage(), e);
        }
    }

    private JsonNode readStreamingMessage(
            InputStream body,
            AgentExecutionObserver observer,
            int step
    ) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, StreamingToolCall> toolCalls = new TreeMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode choice = objectMapper.readTree(data).path("choices").path(0);
                JsonNode delta = choice.path("delta");
                appendTextDelta(delta, "content", content, value -> observer.tokenDelta(step, value));
                appendTextDelta(delta, "reasoning_content", reasoning, ignored -> {
                });
                appendToolCallDeltas(delta.path("tool_calls"), toolCalls);
            }
        }

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (!reasoning.isEmpty()) {
            message.put("reasoning_content", reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            ArrayNode array = objectMapper.createArrayNode();
            toolCalls.values().forEach(call -> array.add(call.toJson(objectMapper)));
            message.set("tool_calls", array);
        }
        return message;
    }

    private void appendTextDelta(
            JsonNode delta,
            String field,
            StringBuilder target,
            java.util.function.Consumer<String> consumer
    ) {
        if (!delta.hasNonNull(field)) {
            return;
        }
        String value = delta.path(field).asText("");
        if (!value.isEmpty()) {
            target.append(value);
            consumer.accept(value);
        }
    }

    private void appendToolCallDeltas(JsonNode deltas, Map<Integer, StreamingToolCall> toolCalls) {
        if (!deltas.isArray()) {
            return;
        }
        for (JsonNode delta : deltas) {
            int index = delta.path("index").asInt(0);
            StreamingToolCall call = toolCalls.computeIfAbsent(index, ignored -> new StreamingToolCall());
            call.append(delta);
        }
    }

    private static final class StreamingToolCall {

        private String id = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void append(JsonNode delta) {
            if (delta.hasNonNull("id")) {
                id = delta.path("id").asText(id);
            }
            JsonNode function = delta.path("function");
            if (function.hasNonNull("name")) {
                name.append(function.path("name").asText());
            }
            if (function.hasNonNull("arguments")) {
                arguments.append(function.path("arguments").asText());
            }
        }

        ObjectNode toJson(ObjectMapper objectMapper) {
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", name.toString());
            function.put("arguments", arguments.toString());
            ObjectNode call = objectMapper.createObjectNode();
            call.put("id", id);
            call.put("type", "function");
            call.set("function", function);
            return call;
        }
    }

    private ArrayNode messagesArray(List<ObjectNode> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ObjectNode message : messages) {
            array.add(message);
        }
        return array;
    }

    private ObjectNode assistantMessageForNextRequest(JsonNode assistantMessage) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        if (assistantMessage.has("content")) {
            message.set("content", assistantMessage.get("content").deepCopy());
        } else {
            message.put("content", "");
        }
        if (assistantMessage.has("reasoning_content")) {
            message.set("reasoning_content", assistantMessage.get("reasoning_content").deepCopy());
        }
        if (assistantMessage.has("tool_calls")) {
            message.set("tool_calls", assistantMessage.get("tool_calls").deepCopy());
        }
        return message;
    }

    private ObjectNode toolResultMessage(JsonNode toolCall, String result) {
        String toolCallId = toolCall.path("id").asText();
        String toolName = toolCall.path("function").path("name").asText();

        ObjectNode message = message("tool", result);
        message.put("tool_call_id", toolCallId);
        message.put("name", toolName);
        return message;
    }

    private String executeTool(String toolName, String arguments) {
        try {
            return switch (toolName) {
                case "fileOperationTool" ->
                        fileOperationTool.apply(objectMapper.readValue(arguments, ToolRegistryConfig.FileRequest.class));
                case "commandExecuteTool" ->
                        commandExecuteTool.apply(objectMapper.readValue(arguments, ToolRegistryConfig.CommandRequest.class));
                case "httpRequestTool" ->
                        httpRequestTool.apply(objectMapper.readValue(arguments, ToolRegistryConfig.WebRequest.class));
                case "knowledgeSearchTool" ->
                        knowledgeSearchTool.apply(objectMapper.readValue(arguments, ToolRegistryConfig.KnowledgeRequest.class));
                default -> "unknown tool: " + toolName;
            };
        } catch (Exception e) {
            return "tool execution failed: " + e.getMessage();
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private ObjectNode thinking() {
        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "enabled");
        return thinking;
    }

    private ArrayNode toolDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(toolDefinition("fileOperationTool", "Write text content to a local file inside the configured workspace.",
                requiredParameters("filePath", "Local file path or file name to write.",
                        "content", "Text content to write to the file.")));
        tools.add(toolDefinition("commandExecuteTool", "Execute a local command only when it is explicitly allowed.",
                requiredParameters("command", "Allowed local command to execute.")));
        tools.add(toolDefinition("httpRequestTool", "Send an HTTP GET request only to configured domains.",
                requiredParameters("url", "HTTP or HTTPS URL to request.")));
        tools.add(toolDefinition("knowledgeSearchTool", "Search the local RAG knowledge base and return chunks with source references.",
                requiredParameters("query", "Question or search query for the local RAG knowledge base.")));
        return tools;
    }

    private ObjectNode toolDefinition(String name, String description, ObjectNode parameters) {
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);

        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    private ObjectNode requiredParameters(String... namesAndDescriptions) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        for (int i = 0; i < namesAndDescriptions.length; i += 2) {
            String name = namesAndDescriptions[i];
            String description = namesAndDescriptions[i + 1];

            ObjectNode property = objectMapper.createObjectNode();
            property.put("type", "string");
            property.put("description", description);
            properties.set(name, property);
            required.add(name);
        }

        parameters.set("properties", properties);
        parameters.set("required", required);
        return parameters;
    }

    private static URI completionsUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/v1/chat/completions");
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }
}
