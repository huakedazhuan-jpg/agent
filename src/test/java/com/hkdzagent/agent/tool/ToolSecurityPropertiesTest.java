package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ToolSecurityPropertiesTest {

    private static final String CLASS_NAME = "com.hkdzagent.agent.tool.ToolSecurityProperties";
    private static final String PREFIX = "agent.tools.security";

    @Test
    void toolSecurityPropertiesUsesExpectedConfigurationPrefix() {
        Class<?> propertiesClass = loadPropertiesClass();

        ConfigurationProperties annotation = propertiesClass.getAnnotation(ConfigurationProperties.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.prefix().isBlank() ? annotation.value() : annotation.prefix())
                .isEqualTo(PREFIX);
    }

    @Test
    void bindsSecuritySettingsFromApplicationStyleProperties() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.tools.security.workspace-root", "C:/agent-workspace")
                .withProperty("agent.tools.security.allowed-commands", "dir,type")
                .withProperty("agent.tools.security.http.allowed-domains", "example.com,api.example.com")
                .withProperty("agent.tools.security.http.connect-timeout", "2s")
                .withProperty("agent.tools.security.http.read-timeout", "5s")
                .withProperty("agent.tools.security.http.max-response-bytes", "4096");

        Object properties = bindProperties(environment);

        assertSecurityProperties(properties);
    }

    @Test
    void bindsSecuritySettingsFromEnvironmentVariables() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                "AGENT_TOOLS_SECURITY_WORKSPACE_ROOT", "C:/agent-workspace",
                "AGENT_TOOLS_SECURITY_ALLOWED_COMMANDS", "dir,type",
                "AGENT_TOOLS_SECURITY_HTTP_ALLOWED_DOMAINS", "example.com,api.example.com",
                "AGENT_TOOLS_SECURITY_HTTP_CONNECT_TIMEOUT", "2s",
                "AGENT_TOOLS_SECURITY_HTTP_READ_TIMEOUT", "5s",
                "AGENT_TOOLS_SECURITY_HTTP_MAX_RESPONSE_BYTES", "4096"
        )));

        Object properties = bindProperties(environment);

        assertSecurityProperties(properties);
    }

    private static Object bindProperties(ConfigurableEnvironment environment) {
        Class<?> propertiesClass = loadPropertiesClass();
        return Binder.get(environment)
                .bind(PREFIX, Bindable.of(propertiesClass))
                .orElseThrow(() -> new AssertionError("Expected security properties to bind from " + PREFIX));
    }

    private static void assertSecurityProperties(Object properties) throws Exception {
        assertThat(invoke(properties, "workspaceRoot")).isEqualTo(Path.of("C:/agent-workspace"));
        assertContainsExactlyInAnyOrder(invoke(properties, "allowedCommands"), "dir", "type");

        Object http = invoke(properties, "http");
        assertContainsExactlyInAnyOrder(invoke(http, "allowedDomains"), "example.com", "api.example.com");
        assertThat(invoke(http, "connectTimeout")).isEqualTo(Duration.ofSeconds(2));
        assertThat(invoke(http, "readTimeout")).isEqualTo(Duration.ofSeconds(5));
        assertThat(((Number) invoke(http, "maxResponseBytes")).longValue()).isEqualTo(4096L);
    }

    private static void assertContainsExactlyInAnyOrder(Object value, String... expected) {
        List<String> actual = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            actual.add(String.valueOf(item));
        }
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Class<?> loadPropertiesClass() {
        try {
            return Class.forName(CLASS_NAME);
        } catch (ClassNotFoundException e) {
            fail("Expected " + CLASS_NAME + " to exist as the tool security @ConfigurationProperties class.");
            return Object.class;
        }
    }
}
