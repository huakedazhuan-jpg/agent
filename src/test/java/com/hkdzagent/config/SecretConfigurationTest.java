package com.hkdzagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretConfigurationTest {

    private static final String APPLICATION_YML = "application.yml";

    @Test
    void applicationYmlDefinesRequiredSecretConfigurationKeys() throws IOException {
        PropertySource<?> applicationYml = loadApplicationYml();

        assertThat(applicationYml.getProperty("spring.ai.openai.api-key")).asString().isNotBlank();
        assertThat(applicationYml.getProperty("spring.ai.openai.chat.options.max-tokens")).asString().isNotBlank();
        assertThat(applicationYml.getProperty("feishu.app-id")).asString().isNotBlank();
        assertThat(applicationYml.getProperty("feishu.app-secret")).asString().isNotBlank();
        assertThat(applicationYml.getProperty("feishu.verification-token")).asString().isNotNull();
        assertThat(applicationYml.getProperty("feishu.encrypt-key")).asString().isNotNull();
        assertThat(applicationYml.getProperty("tavily.api-key")).asString().isNotBlank();
        assertThat(applicationYml.getProperty("spring.config.import"))
                .isEqualTo("optional:file:.env[.properties]");
    }

    @Test
    void applicationYmlConfiguresStockQueryAgentWhitelist() throws IOException {
        PropertySource<?> applicationYml = loadApplicationYml();

        assertThat(applicationYml.getProperty("agent.tools.security.workspace-root"))
                .isEqualTo("${AGENT_WORKSPACE_ROOT:./workspace}");
        assertThat(applicationYml.getProperty("agent.kimi.request-timeout"))
                .isEqualTo("${AGENT_KIMI_REQUEST_TIMEOUT:60s}");
        assertThat(applicationYml.getProperty("agent.kimi.max-tool-rounds"))
                .isEqualTo("${AGENT_KIMI_MAX_TOOL_ROUNDS:5}");
        assertThat(applicationYml.getProperty("agent.kimi.history-limit"))
                .isEqualTo("${AGENT_KIMI_HISTORY_LIMIT:20}");
        assertThat(applicationYml.getProperty("agent.memory.file"))
                .isEqualTo("${AGENT_MEMORY_FILE:data/chat-memory.jsonl}");
        assertThat(applicationYml.getProperty("agent.rag.index-file"))
                .isEqualTo("${AGENT_RAG_INDEX_FILE:data/rag-index.json}");
        assertThat(applicationYml.getProperty("agent.tools.security.allowed-commands"))
                .isNull();
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[0]"))
                .isEqualTo("query1.finance.yahoo.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[1]"))
                .isEqualTo("query2.finance.yahoo.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[2]"))
                .isEqualTo("stooq.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[3]"))
                .isEqualTo("www.google.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[4]"))
                .isEqualTo("www.bing.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[5]"))
                .isEqualTo("duckduckgo.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.allowed-domains[6]"))
                .isEqualTo("www.baidu.com");
        assertThat(applicationYml.getProperty("agent.tools.security.http.connect-timeout"))
                .isEqualTo("2s");
        assertThat(applicationYml.getProperty("agent.tools.security.http.read-timeout"))
                .isEqualTo("5s");
        assertThat(applicationYml.getProperty("agent.tools.security.http.max-response-bytes"))
                .isEqualTo(8192);
    }

    @Test
    void applicationYmlSecretConfigurationValuesAreResolvable() throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", Map.ofEntries(
                Map.entry("MOONSHOT_API_KEY", "moonshot-from-env"),
                Map.entry("MOONSHOT_MAX_TOKENS", "12000"),
                Map.entry("FEISHU_APP_ID", "feishu-app-id-from-env"),
                Map.entry("FEISHU_APP_SECRET", "feishu-secret-from-env"),
                Map.entry("FEISHU_VERIFICATION_TOKEN", "feishu-verification-token-from-env"),
                Map.entry("FEISHU_ENCRYPT_KEY", "feishu-encrypt-key-from-env"),
                Map.entry("FEISHU_ASYNC_CORE_SIZE", "3"),
                Map.entry("FEISHU_ASYNC_MAX_SIZE", "6"),
                Map.entry("FEISHU_ASYNC_QUEUE_CAPACITY", "200"),
                Map.entry("TAVILY_API_KEY", "tavily-from-env"),
                Map.entry("AGENT_KIMI_REQUEST_TIMEOUT", "45s"),
                Map.entry("AGENT_KIMI_MAX_TOOL_ROUNDS", "7"),
                Map.entry("AGENT_KIMI_HISTORY_LIMIT", "30"),
                Map.entry("AGENT_MEMORY_FILE", "data/test-memory.jsonl"),
                Map.entry("AGENT_RAG_INDEX_FILE", "data/test-rag-index.json"),
                Map.entry("AGENT_WORKSPACE_ROOT", "./test-workspace")
        )));
        environment.getPropertySources().addLast(loadApplicationYml());

        assertThat(environment.getProperty("spring.ai.openai.api-key")).isNotBlank().doesNotContain("${");
        assertThat(environment.getProperty("spring.ai.openai.chat.options.max-tokens")).isEqualTo("12000");
        assertThat(environment.getProperty("feishu.app-id")).isNotBlank().doesNotContain("${");
        assertThat(environment.getProperty("feishu.app-secret")).isNotBlank().doesNotContain("${");
        assertThat(environment.getProperty("feishu.verification-token")).isEqualTo("feishu-verification-token-from-env");
        assertThat(environment.getProperty("feishu.encrypt-key")).isEqualTo("feishu-encrypt-key-from-env");
        assertThat(environment.getProperty("feishu.async.core-size")).isEqualTo("3");
        assertThat(environment.getProperty("feishu.async.max-size")).isEqualTo("6");
        assertThat(environment.getProperty("feishu.async.queue-capacity")).isEqualTo("200");
        assertThat(environment.getProperty("tavily.api-key")).isEqualTo("tavily-from-env");
        assertThat(environment.getProperty("agent.kimi.request-timeout")).isEqualTo("45s");
        assertThat(environment.getProperty("agent.kimi.max-tool-rounds")).isEqualTo("7");
        assertThat(environment.getProperty("agent.kimi.history-limit")).isEqualTo("30");
        assertThat(environment.getProperty("agent.memory.file")).isEqualTo("data/test-memory.jsonl");
        assertThat(environment.getProperty("agent.rag.index-file")).isEqualTo("data/test-rag-index.json");
        assertThat(environment.getProperty("agent.tools.security.workspace-root")).isEqualTo("./test-workspace");
    }

    @Test
    void productionSourcesDoNotContainHardcodedTavilyApiKeys() throws IOException {
        String toolRegistrySource = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/tool/ToolRegistryConfig.java"
        ));

        assertThat(toolRegistrySource).doesNotContain("tvly-");
    }

    private PropertySource<?> loadApplicationYml() throws IOException {
        return new YamlPropertySourceLoader()
                .load(APPLICATION_YML, new ClassPathResource(APPLICATION_YML))
                .get(0);
    }
}
