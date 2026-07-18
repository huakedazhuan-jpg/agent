package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class FeishuTenantTokenProvider {

    private final Supplier<FeishuTenantAccessToken> tokenFetcher;
    private final Clock clock;
    private final Duration refreshSkew;
    private FeishuTenantAccessToken cachedToken;

    @Autowired
    public FeishuTenantTokenProvider(FeishuProperties properties) {
        this(() -> fetchToken(properties.appId(), properties.appSecret(), RestClient.create()),
                Clock.systemUTC(), Duration.ofMinutes(2));
    }

    public FeishuTenantTokenProvider(
            Supplier<FeishuTenantAccessToken> tokenFetcher,
            Clock clock,
            Duration refreshSkew
    ) {
        this.tokenFetcher = tokenFetcher;
        this.clock = clock;
        this.refreshSkew = refreshSkew;
    }

    public synchronized String tenantAccessToken() {
        Instant refreshAt = clock.instant().plus(refreshSkew);
        if (cachedToken == null || !cachedToken.expiresAt().isAfter(refreshAt)) {
            cachedToken = tokenFetcher.get();
        }
        return cachedToken.value();
    }

    private static FeishuTenantAccessToken fetchToken(String appId, String appSecret, RestClient restClient) {
        JsonNode response = restClient.post()
                .uri("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_id", appId, "app_secret", appSecret))
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("tenant_access_token")) {
            throw new IllegalStateException("failed to get Feishu tenant_access_token");
        }

        long expiresInSeconds = response.path("expire").asLong(7200);
        return new FeishuTenantAccessToken(
                response.path("tenant_access_token").asText(),
                Instant.now().plusSeconds(expiresInSeconds)
        );
    }
}
