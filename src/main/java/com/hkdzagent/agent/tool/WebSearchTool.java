package com.hkdzagent.agent.tool;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

public class WebSearchTool implements AgentTool<SearchRequest, ToolResult> {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "webSearchTool",
            "1.0.0",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}",
            ToolRiskLevel.LOW,
            ToolApprovalPolicy.NEVER,
            Duration.ofSeconds(10),
            ToolRetryPolicy.fixed(2, Duration.ofMillis(200))
    );

    private final String tavilyApiKey;
    private final RestClient restClient;

    public WebSearchTool(String tavilyApiKey) {
        this(tavilyApiKey, RestClient.create());
    }

    WebSearchTool(String tavilyApiKey, RestClient restClient) {
        this.tavilyApiKey = tavilyApiKey;
        this.restClient = restClient;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public Class<SearchRequest> inputType() {
        return SearchRequest.class;
    }

    @Override
    public ToolResult execute(SearchRequest request) {
        try {
            Map<String, Object> jsonBody = Map.of(
                    "api_key", tavilyApiKey,
                    "query", request.query(),
                    "search_depth", "basic"
            );

            String body = restClient.post()
                    .uri("https://api.tavily.com/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .body(String.class);
            return ToolResult.success(body);
        } catch (Exception e) {
            return ToolResult.failure("search failed: " + e.getMessage());
        }
    }
}
