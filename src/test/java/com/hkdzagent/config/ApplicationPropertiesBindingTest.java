package com.hkdzagent.config;

import com.hkdzagent.agent.AgentApplication;
import com.hkdzagent.agent.ai.ChatMemoryProperties;
import com.hkdzagent.agent.ai.KimiProperties;
import com.hkdzagent.agent.ai.OpenAiCompatibleProperties;
import com.hkdzagent.agent.im.FeishuProperties;
import com.hkdzagent.agent.rag.RagProperties;
import com.hkdzagent.agent.tool.TavilyProperties;
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
    void bindsMemoryRagTavilyAndFeishuProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.memory.file", "data/test-memory.jsonl")
                .withProperty("agent.rag.index-file", "data/test-rag-index.json")
                .withProperty("tavily.api-key", "tavily-api-key")
                .withProperty("feishu.app-id", "feishu-app-id")
                .withProperty("feishu.app-secret", "feishu-app-secret")
                .withProperty("feishu.verification-token", "feishu-token")
                .withProperty("feishu.encrypt-key", "feishu-encrypt-key")
                .withProperty("feishu.async.core-size", "3")
                .withProperty("feishu.async.max-size", "6")
                .withProperty("feishu.async.queue-capacity", "200");

        ChatMemoryProperties memory = bind(environment, "agent.memory", ChatMemoryProperties.class);
        RagProperties rag = bind(environment, "agent.rag", RagProperties.class);
        TavilyProperties tavily = bind(environment, "tavily", TavilyProperties.class);
        FeishuProperties feishu = bind(environment, "feishu", FeishuProperties.class);

        assertThat(memory.file()).isEqualTo(Path.of("data/test-memory.jsonl"));
        assertThat(rag.indexFile()).isEqualTo(Path.of("data/test-rag-index.json"));
        assertThat(tavily.apiKey()).isEqualTo("tavily-api-key");
        assertThat(feishu.appId()).isEqualTo("feishu-app-id");
        assertThat(feishu.appSecret()).isEqualTo("feishu-app-secret");
        assertThat(feishu.verificationToken()).isEqualTo("feishu-token");
        assertThat(feishu.encryptKey()).isEqualTo("feishu-encrypt-key");
        assertThat(feishu.async().coreSize()).isEqualTo(3);
        assertThat(feishu.async().maxSize()).isEqualTo(6);
        assertThat(feishu.async().queueCapacity()).isEqualTo(200);
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
