package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StockQuoteToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsNormalizedStructuredQuote() throws Exception {
        try (TestServer server = startServer(exchange -> writeJson(exchange, 200, """
                {
                  "symbol": "AAPL",
                  "name": "Apple Inc.",
                  "exchange": "NASDAQ",
                  "currency": "USD",
                  "datetime": "2026-07-23",
                  "open": "321.73",
                  "high": "323.25",
                  "low": "319.365",
                  "close": "320.98",
                  "previous_close": "325.89",
                  "change": "-4.91",
                  "percent_change": "-1.50",
                  "volume": "2192344",
                  "is_market_open": true
                }
                """))) {
            StockQuoteTool tool = new StockQuoteTool(properties(server.uri(), "test-key"), objectMapper);

            ToolResult result = tool.execute(new StockQuoteRequest(" aapl "));

            assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCESS);
            JsonNode quote = objectMapper.readTree(result.message());
            assertThat(quote.path("source").asText()).isEqualTo("Twelve Data");
            assertThat(quote.path("symbol").asText()).isEqualTo("AAPL");
            assertThat(quote.path("close").asText()).isEqualTo("320.98");
            assertThat(quote.path("is_market_open").asBoolean()).isTrue();
        }
    }

    @Test
    void reportsProviderErrorWithoutExposingApiKey() throws Exception {
        try (TestServer server = startServer(exchange -> writeJson(exchange, 200, """
                {"code":401,"message":"invalid API key","status":"error"}
                """))) {
            StockQuoteTool tool = new StockQuoteTool(
                    properties(server.uri(), "secret-market-key"),
                    objectMapper
            );

            ToolResult result = tool.execute(new StockQuoteRequest("TSLA"));

            assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.message()).contains("code=401", "invalid API key");
            assertThat(result.message()).doesNotContain("secret-market-key");
        }
    }

    @Test
    void rejectsUnsafeSymbolBeforeNetworkCall() {
        StockQuoteTool tool = new StockQuoteTool(
                properties(URI.create("http://localhost:1/quote"), "test-key"),
                objectMapper
        );

        ToolResult result = tool.execute(new StockQuoteRequest("AAPL&apikey=stolen"));

        assertThat(result.status()).isEqualTo(ToolResult.Status.REJECTED);
        assertThat(result.message()).contains("invalid stock symbol");
    }

    private MarketDataProperties properties(URI baseUrl, String apiKey) {
        MarketDataProperties properties = new MarketDataProperties();
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private TestServer startServer(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0
        );
        server.createContext("/quote", exchange -> handler.handle(exchange));
        server.start();
        return new TestServer(server);
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record TestServer(HttpServer server) implements AutoCloseable {

        URI uri() {
            return URI.create("http://localhost:" + server.getAddress().getPort() + "/quote");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
