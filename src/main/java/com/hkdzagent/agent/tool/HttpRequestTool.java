package com.hkdzagent.agent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

public class HttpRequestTool {

    private final ToolPermissionService permissionService;

    public HttpRequestTool(ToolPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public ToolResult execute(WebRequest request) throws Exception {
        try {
            URI uri = URI.create(request.url());
            if (!permissionService.isHttpUriAllowed(uri)) {
                return ToolResult.rejected("url is not allowed");
            }

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(permissionService.connectTimeout())
                    .build();
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .timeout(permissionService.readTimeout())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream body = response.body()) {
                return ToolResult.success(new String(readLimited(body), StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.rejected("invalid url");
        } catch (HttpTimeoutException e) {
            return ToolResult.failure("network request timeout: " + e.getMessage());
        }
    }

    private byte[] readLimited(InputStream body) throws IOException {
        return body.readNBytes(permissionService.maxResponseBytes());
    }
}
