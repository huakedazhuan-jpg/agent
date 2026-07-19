package com.hkdzagent.config;

import com.hkdzagent.agent.AgentApplication;
import com.hkdzagent.agent.ai.ChatMemoryProperties;
import com.hkdzagent.agent.ai.KimiProperties;
import com.hkdzagent.agent.runtime.AgentRuntimeProperties;
import com.hkdzagent.agent.ai.OpenAiCompatibleProperties;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.im.FeishuProperties;
import com.hkdzagent.agent.rag.RagProperties;
import com.hkdzagent.agent.security.AgentSecurityProperties;
import com.hkdzagent.agent.tool.TavilyProperties;
import com.hkdzagent.agent.trace.AgentTraceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesBindingTest {

    @Test
    void applicationScansTypedConfigurationProperties() {
        assertThat(AgentApplication.class.getAnnotation(ConfigurationPropertiesScan.class)).isNotNull();
    }

    @Test
    void bindsOpenAiCompatibleModelAndKimiRuntimeProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "model-api-key")
                .withProperty("spring.ai.openai.base-url", "https://api.example.test/v1")
                .withProperty("spring.ai.openai.chat.options.model", "kimi-k2.5")
                .withProperty("spring.ai.openai.chat.options.temperature", "0.7")
                .withProperty("spring.ai.openai.chat.options.max-tokens", "12000")
                .withProperty("agent.kimi.request-timeout", "45s")
                .withProperty("agent.kimi.max-tool-rounds", "7")
                .withProperty("agent.kimi.history-limit", "30");

        OpenAiCompatibleProperties model = bind(environment, "spring.ai.openai", OpenAiCompatibleProperties.class);
        KimiProperties kimi = bind(environment, "agent.kimi", KimiProperties.class);

        assertThat(model.apiKey()).isEqualTo("model-api-key");
        assertThat(model.completionsUri()).isEqualTo(URI.create("https://api.example.test/v1/chat/completions"));
        assertThat(model.model()).isEqualTo("kimi-k2.5");
        assertThat(model.temperature()).isEqualTo(0.7);
        assertThat(model.maxTokens()).isEqualTo(12000);
        assertThat(kimi.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(kimi.maxToolRounds()).isEqualTo(7);
        assertThat(kimi.historyLimit()).isEqualTo(30);
    }

    @Test
    void bindsMemoryRagTavilyTraceFeishuAndSecurityProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.memory.file", "data/test-memory.jsonl")
                .withProperty("agent.memory.repository", "jdbc")
                .withProperty("agent.rag.index-file", "data/test-rag-index.json")
                .withProperty("agent.trace.repository", "jdbc")
                .withProperty("agent.tool-approval.repository", "jdbc")
                .withProperty("agent.tool-approval.ttl", "10m")
                .withProperty("agent.tool-approval.required-tools", "fileOperationTool,commandExecuteTool")
                .withProperty("agent.runtime.repository", "jdbc")
                .withProperty("agent.runtime.max-steps", "9")
                .withProperty("agent.runtime.lease-duration", "3m")
                .withProperty("agent.runtime.event-replay-limit", "800")
                .withProperty("agent.security.enabled", "true")
                .withProperty("agent.security.user-repository", "jdbc")
                .withProperty("agent.security.jwt.issuer", "test-agent")
                .withProperty("agent.security.jwt.secret", "test-secret")
                .withProperty("agent.security.jwt.ttl", "45m")
                .withProperty("agent.security.bootstrap.username", "admin")
                .withProperty("agent.security.bootstrap.password", "strong-password")
                .withProperty("agent.security.bootstrap.role", "ADMIN")
                .withProperty("tavily.api-key", "tavily-api-key")
                .withProperty("feishu.app-id", "feishu-app-id")
                .withProperty("feishu.app-secret", "feishu-app-secret")
                .withProperty("feishu.verification-token", "feishu-token")
                .withProperty("feishu.encrypt-key", "feishu-encrypt-key")
                .withProperty("feishu.async.core-size", "3")
                .withProperty("feishu.async.max-size", "6")
                .withProperty("feishu.async.queue-capacity", "200")
                .withProperty("feishu.inbox.repository", "jdbc")
                .withProperty("feishu.inbox.max-attempts", "5")
                .withProperty("feishu.inbox.retry-delay", "45s")
                .withProperty("feishu.inbox.processing-timeout", "10m")
                .withProperty("feishu.inbox.poll-interval", "20s")
                .withProperty("feishu.inbox.poll-batch-size", "50");

        ChatMemoryProperties memory = bind(environment, "agent.memory", ChatMemoryProperties.class);
        RagProperties rag = bind(environment, "agent.rag", RagProperties.class);
        AgentTraceProperties trace = bind(environment, "agent.trace", AgentTraceProperties.class);
        ToolConfirmationProperties toolApproval = bind(
                environment,
                "agent.tool-approval",
                ToolConfirmationProperties.class
        );
        AgentRuntimeProperties runtime = bind(
                environment, "agent.runtime", AgentRuntimeProperties.class);
        AgentSecurityProperties security = bind(environment, "agent.security", AgentSecurityProperties.class);
        TavilyProperties tavily = bind(environment, "tavily", TavilyProperties.class);
        FeishuProperties feishu = bind(environment, "feishu", FeishuProperties.class);

        assertThat(memory.file()).isEqualTo(Path.of("data/test-memory.jsonl"));
        assertThat(memory.repository()).isEqualTo("jdbc");
        assertThat(rag.indexFile()).isEqualTo(Path.of("data/test-rag-index.json"));
        assertThat(trace.repository()).isEqualTo("jdbc");
        assertThat(toolApproval.repository()).isEqualTo("jdbc");
        assertThat(toolApproval.ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(toolApproval.requiredTools())
                .containsExactly("fileOperationTool", "commandExecuteTool");
        assertThat(runtime.getRepository()).isEqualTo("jdbc");
        assertThat(runtime.getMaxSteps()).isEqualTo(9);
        assertThat(runtime.getLeaseDuration()).isEqualTo(Duration.ofMinutes(3));
        assertThat(runtime.getEventReplayLimit()).isEqualTo(800);
        assertThat(security.enabled()).isTrue();
        assertThat(security.userRepository()).isEqualTo("jdbc");
        assertThat(security.jwt().issuer()).isEqualTo("test-agent");
        assertThat(security.jwt().secret()).isEqualTo("test-secret");
        assertThat(security.jwt().ttl()).isEqualTo(Duration.ofMinutes(45));
        assertThat(security.bootstrap().username()).isEqualTo("admin");
        assertThat(security.bootstrap().password()).isEqualTo("strong-password");
        assertThat(security.bootstrap().role()).isEqualTo("ADMIN");
        assertThat(tavily.apiKey()).isEqualTo("tavily-api-key");
        assertThat(feishu.appId()).isEqualTo("feishu-app-id");
        assertThat(feishu.appSecret()).isEqualTo("feishu-app-secret");
        assertThat(feishu.verificationToken()).isEqualTo("feishu-token");
        assertThat(feishu.encryptKey()).isEqualTo("feishu-encrypt-key");
        assertThat(feishu.async().coreSize()).isEqualTo(3);
        assertThat(feishu.async().maxSize()).isEqualTo(6);
        assertThat(feishu.async().queueCapacity()).isEqualTo(200);
        assertThat(feishu.inbox().repository()).isEqualTo("jdbc");
        assertThat(feishu.inbox().maxAttempts()).isEqualTo(5);
        assertThat(feishu.inbox().retryDelay()).isEqualTo(Duration.ofSeconds(45));
        assertThat(feishu.inbox().processingTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(feishu.inbox().pollInterval()).isEqualTo(Duration.ofSeconds(20));
        assertThat(feishu.inbox().pollBatchSize()).isEqualTo(50);
    }

    @Test
    void productionCodeDoesNotUseValueInjectionForApplicationConfiguration() throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            String productionSource = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(ApplicationPropertiesBindingTest::readString)
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(productionSource)
                    .doesNotContain("@Value(")
                    .doesNotContain("org.springframework.beans.factory.annotation.Value");
        }
    }

    private static <T> T bind(MockEnvironment environment, String prefix, Class<T> type) {
        return Binder.get(environment)
                .bind(prefix, Bindable.of(type))
                .orElseThrow(() -> new AssertionError("Expected " + type.getName() + " to bind from " + prefix));
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
