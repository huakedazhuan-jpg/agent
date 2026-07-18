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
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", Map.of(
                "MOONSHOT_API_KEY", "moonshot-from-env",
                "FEISHU_APP_ID", "feishu-app-id-from-env",
                "FEISHU_APP_SECRET", "feishu-secret-from-env",
                "FEISHU_VERIFICATION_TOKEN", "feishu-verification-token-from-env",
                "FEISHU_ENCRYPT_KEY", "feishu-encrypt-key-from-env",
                "TAVILY_API_KEY", "tavily-from-env",
                "AGENT_WORKSPACE_ROOT", "./test-workspace"
        )));
        environment.getPropertySources().addLast(loadApplicationYml());

        assertThat(environment.getProperty("spring.ai.openai.api-key")).isNotBlank().doesNotContain("${");
        assertThat(environment.getProperty("feishu.app-id")).isNotBlank().doesNotContain("${");
        assertThat(environment.getProperty("feishu.app-secret")).isNotBlank().doesNotContain("${");
        assertThat(environment.getProperty("feishu.verification-token")).isEqualTo("feishu-verification-token-from-env");
        assertThat(environment.getProperty("feishu.encrypt-key")).isEqualTo("feishu-encrypt-key-from-env");
        assertThat(environment.getProperty("tavily.api-key")).isEqualTo("tavily-from-env");
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
