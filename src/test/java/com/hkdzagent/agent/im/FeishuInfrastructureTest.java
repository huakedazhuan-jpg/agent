package com.hkdzagent.agent.im;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class FeishuInfrastructureTest {

    private static final String IM_PACKAGE = "com.hkdzagent.agent.im.";

    @Test
    void verifiesCallbackTokenAndOptionalSignature() throws Exception {
        Object verifier = newInstance("FeishuSignatureVerifier", "expected-token", "encrypt-key");
        String body = """
                {
                  "header": {
                    "token": "expected-token"
                  }
                }
                """;
        Map<String, String> validHeaders = Map.of(
                "X-Lark-Request-Timestamp", "1710000000",
                "X-Lark-Request-Nonce", "nonce",
                "X-Lark-Signature", signature("1710000000", "nonce", "encrypt-key", body)
        );
        Map<String, String> invalidHeaders = Map.of(
                "X-Lark-Request-Timestamp", "1710000000",
                "X-Lark-Request-Nonce", "nonce",
                "X-Lark-Signature", "bad-signature"
        );

        assertThat(invoke(verifier, "verify", Map.of(), body)).isEqualTo(true);
        assertThat(invoke(verifier, "verify", validHeaders, body)).isEqualTo(true);
        assertThat(invoke(verifier, "verify", invalidHeaders, body)).isEqualTo(false);
        assertThat(invoke(verifier, "verify", Map.of(), body.replace("expected-token", "wrong-token")))
                .isEqualTo(false);
    }

    @Test
    void cachesTenantAccessTokenUntilRefreshWindow() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-16T00:00:00Z"));
        AtomicInteger fetches = new AtomicInteger();
        Supplier<Object> fetcher = () -> {
            try {
                return newInstance(
                        "FeishuTenantAccessToken",
                        "token-" + fetches.incrementAndGet(),
                        clock.instant().plusSeconds(3600)
                );
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Object provider = newInstance("FeishuTenantTokenProvider", fetcher, clock, Duration.ofSeconds(60));

        assertThat(invoke(provider, "tenantAccessToken")).isEqualTo("token-1");
        assertThat(invoke(provider, "tenantAccessToken")).isEqualTo("token-1");
        assertThat(fetches).hasValue(1);

        clock.advance(Duration.ofSeconds(3541));

        assertThat(invoke(provider, "tenantAccessToken")).isEqualTo("token-2");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void webhookControllerUsesManagedProcessorInsteadOfRawThread() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/im/FeishuWebhookController.java"
        ));

        assertThat(source).doesNotContain("new Thread");
        assertThat(source).contains("FeishuEventProcessor");
    }

    private static String signature(String timestamp, String nonce, String encryptKey, String body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest((timestamp + nonce + encryptKey + body).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Object newInstance(String simpleName, Object... args) throws Exception {
        Class<?> targetClass = load(simpleName);
        for (Constructor<?> constructor : targetClass.getConstructors()) {
            if (constructor.getParameterCount() == args.length) {
                return constructor.newInstance(args);
            }
        }
        fail("Expected " + targetClass.getName() + " to have a public constructor with "
                + args.length + " argument(s).");
        return null;
    }

    private static Object invoke(Object target, String methodName, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                return method.invoke(target, args);
            }
        }
        fail("Expected " + target.getClass().getName() + " to expose method " + methodName
                + " with " + args.length + " argument(s).");
        return null;
    }

    private static Class<?> load(String simpleName) {
        String className = IM_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
