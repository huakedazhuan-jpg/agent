package com.hkdzagent.agent.tool;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

public class WebSearchTool {

    private final String tavilyApiKey;
    private final RestClient restClient;

    public WebSearchTool(String tavilyApiKey) {
        this(tavilyApiKey, RestClient.create());
    }

    WebSearchTool(String tavilyApiKey, RestClient restClient) {
        this.tavilyApiKey = tavilyApiKey;
        this.restClient = restClient;
    }

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
