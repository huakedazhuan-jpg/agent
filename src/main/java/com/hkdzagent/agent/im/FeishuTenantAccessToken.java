package com.hkdzagent.agent.im;

import java.time.Instant;

public record FeishuTenantAccessToken(String value, Instant expiresAt) {
}
