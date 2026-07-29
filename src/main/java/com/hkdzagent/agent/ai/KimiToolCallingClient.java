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
import com.hkdzagent.agent.context.ConservativeTokenCounter;
import com.hkdzagent.agent.context.ContextAssembler;
import com.hkdzagent.agent.context.ContextBudget;
import com.hkdzagent.agent.context.ContextEnvelope;
import com.hkdzagent.agent.context.ContextRequest;
import com.hkdzagent.agent.context.ContextSection;
import com.hkdzagent.agent.context.DefaultContextSource;
import com.hkdzagent.agent.memory.InMemoryMemoryRepository;
import com.hkdzagent.agent.memory.MemoryRetriever;
import com.hkdzagent.agent.memory.MemorySanitizer;
import com.hkdzagent.agent.memory.OwnedConversationId;
import com.hkdzagent.agent.tool.ToolRegistryConfig;
import org.springframework.ai.chat.memory.ChatMemory;
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
import java.time.LocalDate;
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
            查询股票最新价格、开高低收、成交量或涨跌幅时，必须优先使用 stockQuoteTool，并且只传股票代码。
            如果 stockQuoteTool 返回失败、无权限或缺少数据，使用 webSearchTool 搜索公开网页作为兜底，不要反复调用相同失败的行情代码。
            使用 webSearchTool 时必须在查询词中加入当前年份。网页搜索结果不得冒充实时行情；只有结果明确包含数据日期时才能报告价格。
            回答必须逐项注明网页来源和数据日期；来源冲突或日期不明确时，应明确说无法确认当前价格，不得自行拼接或推测。
            不要使用 httpRequestTool 猜测 Yahoo Finance、Stooq 或其他行情接口 URL。
            只有 stockQuoteTool 明确无法覆盖、且用户需要普通公开网页内容时，才可以使用其他查询工具。
            回答时要注明数据来源和查询时间，说明行情数据可能延迟或缺失。
            只做信息查询、整理和解释，不提供投资建议，不承诺收益，不替用户做买卖决策。
            """;

    private final ObjectMapper objectMapper;
    private final ContextAssembler contextAssembler;
    private final ChatMemory legacyChatMemory;
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
    private final Function<ToolRegistryConfig.StockRequest, String> stockQuoteTool;
    private final Function<ToolRegistryConfig.SearchRequest, String> webSearchTool;
    private final Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool;

    @Autowired
    public KimiToolCallingClient(
            ObjectMapper objectMapper,
            ChatMemory chatMemory,
            ContextAssembler contextAssembler,
            OpenAiCompatibleProperties modelProperties,
            KimiProperties kimiProperties,
            @Qualifier("fileOperationTool") Function<ToolRegistryConfig.FileRequest, String> fileOperationTool,
            @Qualifier("commandExecuteTool") Function<ToolRegistryConfig.CommandRequest, String> commandExecuteTool,
            @Qualifier("httpRequestTool") Function<ToolRegistryConfig.WebRequest, String> httpRequestTool,
            @Qualifier("stockQuoteTool") Function<ToolRegistryConfig.StockRequest, String> stockQuoteTool,
            @Qualifier("webSearchTool") Function<ToolRegistryConfig.SearchRequest, String> webSearchTool,
            @Qualifier("knowledgeSearchTool") Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool
    ) {
        this(objectMapper, contextAssembler, null, modelProperties.apiKey(), modelProperties.completionsUri(),
                modelProperties.model(), modelProperties.temperature(), modelProperties.maxTokens(),
                kimiProperties.requestTimeout(), kimiProperties.maxToolRounds(), kimiProperties.historyLimit(),
                fileOperationTool, commandExecuteTool, httpRequestTool, stockQuoteTool, webSearchTool,
                knowledgeSearchTool);
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
        this(objectMapper, legacyContextAssembler(chatMemory), chatMemory, apiKey, completionsUri(baseUrl), model, temperature, maxTokens, requestTimeout,
                maxToolRounds, 20, fileOperationTool, commandExecuteTool, httpRequestTool,
                unavailableStockQuoteTool(), unavailableWebSearchTool(), knowledgeSearchTool);
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
        this(objectMapper, legacyContextAssembler(chatMemory), chatMemory, apiKey, completionsUri(baseUrl), model, temperature, maxTokens, requestTimeout,
                maxToolRounds, 20, fileOperationTool, commandExecuteTool, httpRequestTool,
                unavailableStockQuoteTool(),
                unavailableWebSearchTool(),
                request -> "knowledge search unavailable: no local RAG knowledge base is configured");
    }

    private KimiToolCallingClient(
            ObjectMapper objectMapper,
            ContextAssembler contextAssembler,
            ChatMemory legacyChatMemory,
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
            Function<ToolRegistryConfig.StockRequest, String> stockQuoteTool,
            Function<ToolRegistryConfig.SearchRequest, String> webSearchTool,
            Function<ToolRegistryConfig.KnowledgeRequest, String> knowledgeSearchTool
    ) {
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
        this.legacyChatMemory = legacyChatMemory;
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
        this.stockQuoteTool = stockQuoteTool;
        this.webSearchTool = webSearchTool;
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
        OwnedConversationId identity = OwnedConversationId.decodeOrLegacy(conversationId);
        String ownerKey = observer == null || observer.contextOwnerKey() == null
                ? identity.owner().key()
                : observer.contextOwnerKey();
        String runId = observer == null || observer.contextRunId() == null
                ? traceId
                : observer.contextRunId();
        return runWithTools(
                userMessage, conversationId, ownerKey, runId, traceId, observer);
    }

    public AgentLoopResult runWithTools(
            String userMessage,
            String conversationId,
            String ownerKey,
            String runId,
            String traceId,
            AgentExecutionObserver observer
    ) {
        ContextRequest request = new ContextRequest(
                ownerKey, normalizeSessionId(conversationId), runId,
                SYSTEM_PROMPT + "\n当前日期：" + LocalDate.now() + "。",
                userMessage, List.of());
        ContextEnvelope envelope = contextAssembler.assemble(request);
        List<ObjectNode> messages = envelopeMessages(envelope);
        AgentExecutionObserver safeObserver = observer == null ? AgentExecutionObserver.NOOP : observer;
        safeObserver.contextAssembled(0, envelope);
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
            enforceProtocolBudget(messages);

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
        enforceProtocolBudget(messages);

        observer.modelStarted(turn.step());
        JsonNode assistantMessage = callModel(messages, observer, turn.step());
        observer.modelCompleted(turn.step());
        JsonNode toolCalls = assistantMessage.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            String answer = assistantMessage.path("content").asText("");
            if (legacyChatMemory != null) {
                legacyChatMemory.add(conversationId, List.of(
                        new org.springframework.ai.chat.messages.UserMessage(userMessage),
                        new org.springframework.ai.chat.messages.AssistantMessage(answer)));
            }
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

    private List<ObjectNode> envelopeMessages(ContextEnvelope envelope) {
        List<ObjectNode> messages = new ArrayList<>();
        for (ContextSection section : envelope.messages()) {
            messages.add(message(section.role(), section.content()));
        }

        return messages;
    }

    private void enforceProtocolBudget(List<ObjectNode> messages) {
        int toolLimit = contextAssembler.toolObservationTokenLimit();
        for (ObjectNode candidate : messages) {
            if ("tool".equals(candidate.path("role").asText())) {
                String content = candidate.path("content").asText("");
                if (contextAssembler.countTokens(content) > toolLimit) {
                    candidate.put("content", truncateToolResult(content, toolLimit));
                }
            }
        }
        while (protocolTokens(messages) > contextAssembler.usableTokenLimit()) {
            int toolIndex = -1;
            for (int i = 0; i < messages.size(); i++) {
                if ("tool".equals(messages.get(i).path("role").asText())) {
                    toolIndex = i;
                    break;
                }
            }
            if (toolIndex < 0) {
                throw new com.hkdzagent.agent.context.ContextLimitExceededException(
                        "model protocol messages exceed the input budget");
            }
            String toolCallId = messages.get(toolIndex).path("tool_call_id").asText();
            messages.remove(toolIndex);
            for (int i = 0; i < messages.size(); i++) {
                JsonNode calls = messages.get(i).path("tool_calls");
                boolean match = false;
                if (calls.isArray()) {
                    for (JsonNode call : calls) {
                        match |= toolCallId.equals(call.path("id").asText());
                    }
                }
                if (match) {
                    messages.remove(i);
                    break;
                }
            }
        }
    }

    private int protocolTokens(List<ObjectNode> messages) {
        return messages.stream()
                .mapToInt(value -> contextAssembler.countTokens(value.toString()))
                .sum();
    }

    private String truncateToolResult(String content, int limit) {
        String suffix = "\n[工具结果已按上下文预算压缩]";
        int target = Math.max(0, limit - contextAssembler.countTokens(suffix));
        int low = 0;
        int high = content.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (contextAssembler.countTokens(content.substring(0, middle)) <= target) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return content.substring(0, low) + suffix;
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
                case "stockQuoteTool" ->
                        stockQuoteTool.apply(objectMapper.readValue(arguments, ToolRegistryConfig.StockRequest.class));
                case "webSearchTool" ->
                        webSearchTool.apply(objectMapper.readValue(arguments, ToolRegistryConfig.SearchRequest.class));
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
        tools.add(toolDefinition(
                "stockQuoteTool",
                "Query a structured stock quote. Prefer this over guessing finance website URLs.",
                requiredParameters("symbol", "Ticker symbol such as AAPL, TSLA, IBM, or 0700.HK.")
        ));
        tools.add(toolDefinition(
                "webSearchTool",
                "Search public websites. Use this as the fallback when stockQuoteTool fails or lacks data; include source and data time in the final answer.",
                requiredParameters("query", "Precise public-web search query, including ticker symbol and requested market data.")
        ));
        tools.add(toolDefinition("knowledgeSearchTool", "Search the local RAG knowledge base and return chunks with source references.",
                requiredParameters("query", "Question or search query for the local RAG knowledge base.")));
        return tools;
    }

    private static Function<ToolRegistryConfig.StockRequest, String> unavailableStockQuoteTool() {
        return request -> "stock quote unavailable: no market-data provider is configured";
    }

    private static Function<ToolRegistryConfig.SearchRequest, String> unavailableWebSearchTool() {
        return request -> "web search unavailable: no public-web search provider is configured";
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

    private static ContextAssembler legacyContextAssembler(ChatMemory chatMemory) {
        MemorySanitizer sanitizer = new MemorySanitizer();
        return new ContextAssembler(
                new DefaultContextSource(
                        chatMemory, null, null,
                        new MemoryRetriever(new InMemoryMemoryRepository(), sanitizer)),
                new ConservativeTokenCounter(),
                new ContextBudget());
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
