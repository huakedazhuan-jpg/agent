package com.hkdzagent.config;

import com.hkdzagent.agent.config.ProductionConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ProductionConfigurationValidator.class);

    @Test
    void allowsDevelopmentProfileWithoutProductionOnlySecrets() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsFastWhenProductionSecretsAreMissing() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.ai.openai.api-key=prod-openai-key",
                        "spring.ai.openai.base-url=https://api.moonshot.ai",
                        "spring.ai.openai.chat.options.model=kimi-k2.5",
                        "feishu.app-id=prod-feishu-app",
                        "feishu.app-secret=prod-feishu-secret",
                        "tavily.api-key=prod-tavily-key",
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: feishu.verification-token, feishu.encrypt-key");
                });
    }

    @Test
    void failsFastWhenProductionUsesPlaceholderOrDefaultValues() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.ai.openai.api-key=test-openai-key",
                        "spring.ai.openai.base-url=https://api.moonshot.ai",
                        "spring.ai.openai.chat.options.model=kimi-k2.5",
                        "feishu.app-id=prod-feishu-app",
                        "feishu.app-secret=prod-feishu-secret",
                        "feishu.verification-token=changeme",
                        "feishu.encrypt-key=prod-feishu-encrypt-key",
                        "tavily.api-key=prod-tavily-key",
                        "agent.tools.security.workspace-root=./workspace"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: spring.ai.openai.api-key, feishu.verification-token, agent.tools.security.workspace-root must be explicit in prod");
                });
    }

    @Test
    void allowsProductionWhenRequiredConfigurationIsExplicit() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.ai.openai.api-key=prod-openai-key",
                        "spring.ai.openai.base-url=https://api.moonshot.ai",
                        "spring.ai.openai.chat.options.model=kimi-k2.5",
                        "feishu.app-id=prod-feishu-app",
                        "feishu.app-secret=prod-feishu-secret",
                        "feishu.verification-token=prod-feishu-verification-token",
                        "feishu.encrypt-key=prod-feishu-encrypt-key",
                        "tavily.api-key=prod-tavily-key",
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }
}
