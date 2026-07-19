package com.hkdzagent.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class ProductionConfigurationValidator implements InitializingBean {

    private static final String PROD_PROFILE = "prod";
    private static final List<String> REQUIRED_PRODUCTION_PROPERTIES = List.of(
            "spring.ai.openai.api-key",
            "spring.ai.openai.base-url",
            "spring.ai.openai.chat.options.model",
            "feishu.app-id",
            "feishu.app-secret",
            "feishu.verification-token",
            "feishu.encrypt-key",
            "tavily.api-key",
            "agent.tools.security.workspace-root",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.data.redis.host",
            "spring.data.redis.password"
    );

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!productionProfileActive()) {
            return;
        }

        List<String> unsafeProperties = new ArrayList<>(REQUIRED_PRODUCTION_PROPERTIES.stream()
                .filter(this::isUnsafeProperty)
                .toList());

        if (usesDefaultWorkspaceRoot()) {
            unsafeProperties.add("agent.tools.security.workspace-root must be explicit in prod");
        }

        if (!flywayEnabled()) {
            unsafeProperties.add("spring.flyway.enabled must be true in prod");
        }

        if (!jdbcTraceRepositoryEnabled()) {
            unsafeProperties.add("agent.trace.repository must be jdbc in prod");
        }

        if (!jdbcChatMemoryEnabled()) {
            unsafeProperties.add("agent.memory.repository must be jdbc in prod");
        }

        if (!jdbcToolApprovalRepositoryEnabled()) {
            unsafeProperties.add("agent.tool-approval.repository must be jdbc in prod");
        }

        if (!jdbcFeishuInboxRepositoryEnabled()) {
            unsafeProperties.add("feishu.inbox.repository must be jdbc in prod");
        }

        if (!unsafeProperties.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join(", ", unsafeProperties));
        }
    }

    private boolean productionProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> PROD_PROFILE.equalsIgnoreCase(profile));
    }

    private boolean isUnsafeProperty(String propertyName) {
        String value = environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("${")
                || normalized.equals("changeme")
                || normalized.equals("change-me")
                || normalized.equals("todo")
                || normalized.equals("placeholder")
                || normalized.startsWith("test-")
                || normalized.startsWith("dummy-")
                || normalized.startsWith("your-")
                || normalized.startsWith("xingclaw-local-");
    }

    private boolean usesDefaultWorkspaceRoot() {
        String workspaceRoot = environment.getProperty("agent.tools.security.workspace-root");
        if (workspaceRoot == null) {
            return false;
        }
        String normalized = workspaceRoot.trim().replace('\\', '/');
        return normalized.equals(".")
                || normalized.equals("./workspace")
                || normalized.equals("workspace");
    }

    private boolean flywayEnabled() {
        return environment.getProperty("spring.flyway.enabled", Boolean.class, false);
    }

    private boolean jdbcTraceRepositoryEnabled() {
        String repository = environment.getProperty("agent.trace.repository", "");
        return "jdbc".equalsIgnoreCase(repository.trim());
    }

    private boolean jdbcChatMemoryEnabled() {
        String repository = environment.getProperty("agent.memory.repository", "");
        return "jdbc".equalsIgnoreCase(repository.trim());
    }

    private boolean jdbcToolApprovalRepositoryEnabled() {
        String repository = environment.getProperty("agent.tool-approval.repository", "");
        return "jdbc".equalsIgnoreCase(repository.trim());
    }

    private boolean jdbcFeishuInboxRepositoryEnabled() {
        String repository = environment.getProperty("feishu.inbox.repository", "");
        return "jdbc".equalsIgnoreCase(repository.trim());
    }
}
