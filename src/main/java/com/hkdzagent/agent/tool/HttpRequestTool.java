package com.hkdzagent.agent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class HttpRequestTool implements AgentTool<WebRequest, ToolResult> {

    private static final int MAX_REDIRECTS = 3;
    private static final ToolMetadata METADATA = new ToolMetadata(
            "httpRequestTool",
            "1.0.0",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"],\"additionalProperties\":false}",
            ToolRiskLevel.MEDIUM,
            ToolApprovalPolicy.CONDITIONAL,
            Duration.ofSeconds(5),
            ToolRetryPolicy.none()
    );

    private final ToolPermissionService permissionService;

    public HttpRequestTool(ToolPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public Class<WebRequest> inputType() {
        return WebRequest.class;
    }

    @Override
    public ToolResult execute(WebRequest request) throws Exception {
        try {
            URI uri = parseUri(request.url());
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(permissionService.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                if (!permissionService.isHttpUriAllowed(uri)) {
                    return ToolResult.rejected("url is not allowed");
                }

                HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                        .timeout(permissionService.readTimeout())
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream()
                );

                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (isRedirect(status)) {
                        if (redirectCount == MAX_REDIRECTS) {
                            return ToolResult.failure("http request failed: too many redirects");
                        }
                        String location = response.headers().firstValue("Location").orElse("");
                        if (location.isBlank()) {
                            return ToolResult.failure(
                                    "http request failed: status=" + status + ", missing redirect location");
                        }
                        uri = uri.resolve(location);
                        continue;
                    }

                    byte[] responseBytes = readLimited(body);
                    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
                    if (status < 200 || status >= 300) {
                        return ToolResult.failure(httpFailure(status, response, responseBody));
                    }
                    return ToolResult.success(responseBody);
                }
            }
            return ToolResult.failure("http request failed: too many redirects");
        } catch (IllegalArgumentException e) {
            return ToolResult.rejected("invalid url");
        } catch (HttpTimeoutException e) {
            return ToolResult.failure("network request timeout: " + e.getMessage());
        }
    }

    private URI parseUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is blank");
        }
        return URI.create(url.replace("^", "%5E"));
    }

    private boolean isRedirect(int status) {
        return status == 301
                || status == 302
                || status == 303
                || status == 307
                || status == 308;
    }

    private String httpFailure(
            int status,
            HttpResponse<InputStream> response,
            String responseBody
    ) {
        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElse("unknown");
        String bodyPreview = responseBody == null
                ? ""
                : responseBody.replaceAll("\\s+", " ").strip();
        if (bodyPreview.length() > 160) {
            bodyPreview = bodyPreview.substring(0, 160) + "...";
        }
        String message = "http request failed: status=" + status + ", content-type=" + contentType;
        return bodyPreview.isEmpty() ? message : message + ", body=" + bodyPreview;
    }

    private byte[] readLimited(InputStream body) throws IOException {
        return body.readNBytes(permissionService.maxResponseBytes());
    }
}
