package com.hkdzagent.config;

import com.hkdzagent.agent.config.ProductionConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigurationValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("agent.runtime.repository=jdbc")
            .withPropertyValues("feishu.outbox.repository=jdbc")
            .withPropertyValues("agent.audit.repository=jdbc")
            .withBean(ProductionConfigurationValidator.class);

    @Test
    void failsFastWhenProductionUsesInMemoryAgentRuntime() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "agent.runtime.repository=memory"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("agent.runtime.repository must be jdbc in prod");
                });
    }

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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
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
                        "agent.tools.security.workspace-root=./workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsFastWhenProductionInfrastructureUsesLocalDefaultsOrDisablesFlyway() {
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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=xingclaw-local-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=xingclaw-local-redis",
                        "spring.flyway.enabled=false",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: spring.datasource.password, spring.data.redis.password, spring.flyway.enabled must be true in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesInMemoryTraceRepository() {
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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=memory",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.trace.repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesFileChatMemoryRepository() {
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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=file",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.memory.repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesInMemoryToolApprovalRepository() {
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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=memory",
                        "feishu.inbox.repository=jdbc",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.tool-approval.repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesInMemoryFeishuInboxRepository() {
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
                        "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                        "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                        "spring.datasource.username=prod_agent",
                        "spring.datasource.password=prod-postgres-password",
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.password=prod-redis-password",
                        "spring.flyway.enabled=true",
                        "agent.trace.repository=jdbc",
                        "agent.memory.repository=jdbc",
                        "agent.tool-approval.repository=jdbc",
                        "feishu.inbox.repository=memory",
                        "agent.security.enabled=true",
                        "agent.security.user-repository=jdbc",
                        "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: feishu.inbox.repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionAuthenticationIsDisabled() {
        contextRunner
                .withPropertyValues(safeProductionProperties())
                .withPropertyValues("agent.security.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.security.enabled must be true in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesInMemoryFeishuOutboxRepository() {
        contextRunner
                .withPropertyValues(safeProductionProperties())
                .withPropertyValues("feishu.outbox.repository=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: feishu.outbox.repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesInMemoryAdminAuditRepository() {
        contextRunner
                .withPropertyValues(safeProductionProperties())
                .withPropertyValues("agent.audit.repository=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.audit.repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionUsesInMemoryUserRepository() {
        contextRunner
                .withPropertyValues(safeProductionProperties())
                .withPropertyValues("agent.security.user-repository=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.security.user-repository must be jdbc in prod");
                });
    }

    @Test
    void failsFastWhenProductionJwtSecretIsTooShort() {
        contextRunner
                .withPropertyValues(safeProductionProperties())
                .withPropertyValues("agent.security.jwt.secret=too-short")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Unsafe production configuration: agent.security.jwt.secret must contain at least 32 bytes in prod");
                });
    }

    private String[] safeProductionProperties() {
        return new String[]{
                "spring.profiles.active=prod",
                "spring.ai.openai.api-key=prod-openai-key",
                "spring.ai.openai.base-url=https://api.moonshot.ai",
                "spring.ai.openai.chat.options.model=kimi-k2.5",
                "feishu.app-id=prod-feishu-app",
                "feishu.app-secret=prod-feishu-secret",
                "feishu.verification-token=prod-feishu-verification-token",
                "feishu.encrypt-key=prod-feishu-encrypt-key",
                "tavily.api-key=prod-tavily-key",
                "agent.tools.security.workspace-root=/srv/xingclaw-agent/workspace",
                "spring.datasource.url=jdbc:postgresql://postgres.internal:5432/xingclaw_agent",
                "spring.datasource.username=prod_agent",
                "spring.datasource.password=prod-postgres-password",
                "spring.data.redis.host=redis.internal",
                "spring.data.redis.password=prod-redis-password",
                "spring.flyway.enabled=true",
                "agent.trace.repository=jdbc",
                "agent.memory.repository=jdbc",
                "agent.tool-approval.repository=jdbc",
                "feishu.inbox.repository=jdbc",
                "agent.security.enabled=true",
                "agent.security.user-repository=jdbc",
                "agent.security.jwt.secret=prod-jwt-secret-with-at-least-32-bytes"
        };
    }
}
