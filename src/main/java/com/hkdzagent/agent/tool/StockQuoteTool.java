package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

public class StockQuoteTool implements AgentTool<StockQuoteRequest, ToolResult> {

    private static final Pattern SAFE_SYMBOL = Pattern.compile("[A-Za-z0-9._:-]{1,32}");
    private static final ToolMetadata METADATA = new ToolMetadata(
            "stockQuoteTool",
            "1.0.0",
            "{\"type\":\"object\",\"properties\":{\"symbol\":{\"type\":\"string\","
                    + "\"description\":\"Ticker symbol such as AAPL, TSLA, IBM, or 0700.HK\"}},"
                    + "\"required\":[\"symbol\"],\"additionalProperties\":false}",
            ToolRiskLevel.LOW,
            ToolApprovalPolicy.NEVER,
            Duration.ofSeconds(45),
            ToolRetryPolicy.fixed(2, Duration.ofMillis(250))
    );

    private final MarketDataProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public StockQuoteTool(MarketDataProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
        );
    }

    StockQuoteTool(
            MarketDataProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public Class<StockQuoteRequest> inputType() {
        return StockQuoteRequest.class;
    }

    @Override
    public ToolResult execute(StockQuoteRequest request) {
        String symbol = normalizeSymbol(request == null ? null : request.symbol());
        if (symbol == null) {
            return ToolResult.rejected("invalid stock symbol");
        }
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.failure(
                    "stock quote unavailable: TWELVE_DATA_API_KEY is not configured");
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return requestQuote(symbol, apiKey);
            } catch (HttpTimeoutException e) {
                if (attempt == 2) {
                    return ToolResult.failure("stock quote request timed out after 2 attempts");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("stock quote request interrupted");
            } catch (IOException | IllegalArgumentException e) {
                return ToolResult.failure("stock quote request failed: " + e.getMessage());
            }
        }
        return ToolResult.failure("stock quote request failed");
    }

    private ToolResult requestQuote(String symbol, String apiKey)
            throws IOException, InterruptedException {
            HttpRequest httpRequest = HttpRequest.newBuilder(requestUri(symbol, apiKey))
                    .timeout(properties.readTimeout())
                    .header("Accept", "application/json")
                    .header("User-Agent", "XingClaw-Agent/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ToolResult.failure(
                        "stock quote request failed: status=" + response.statusCode());
            }

            JsonNode body = objectMapper.readTree(response.body());
            if ("error".equalsIgnoreCase(body.path("status").asText())
                    || body.hasNonNull("code") && body.hasNonNull("message")) {
                return ToolResult.failure(
                        "stock quote provider rejected request: code="
                                + body.path("code").asText("unknown")
                                + ", message=" + body.path("message").asText("unknown error"));
            }
            if (!body.hasNonNull("close") || body.path("close").asText().isBlank()) {
                return ToolResult.failure("stock quote response did not contain a price");
            }

            return ToolResult.success(normalizedQuote(body));
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        String normalized = symbol.strip().toUpperCase(Locale.ROOT);
        return SAFE_SYMBOL.matcher(normalized).matches() ? normalized : null;
    }

    private URI requestUri(String symbol, String apiKey) {
        String baseUrl = properties.baseUrl().toString();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return URI.create(baseUrl + separator
                + "symbol=" + encode(symbol)
                + "&apikey=" + encode(apiKey));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizedQuote(JsonNode body) throws IOException {
        ObjectNode quote = objectMapper.createObjectNode();
        quote.put("source", "Twelve Data");
        copy(body, quote, "symbol");
        copy(body, quote, "name");
        copy(body, quote, "exchange");
        copy(body, quote, "currency");
        copy(body, quote, "datetime");
        copy(body, quote, "open");
        copy(body, quote, "high");
        copy(body, quote, "low");
        copy(body, quote, "close");
        copy(body, quote, "previous_close");
        copy(body, quote, "change");
        copy(body, quote, "percent_change");
        copy(body, quote, "volume");
        if (body.has("is_market_open")) {
            quote.set("is_market_open", body.get("is_market_open"));
        }
        quote.put("freshness_notice",
                "Market data may be delayed depending on the provider plan and exchange.");
        return objectMapper.writeValueAsString(quote);
    }

    private void copy(JsonNode source, ObjectNode target, String field) {
        if (source.hasNonNull(field)) {
            target.set(field, source.get(field));
        }
    }
}
