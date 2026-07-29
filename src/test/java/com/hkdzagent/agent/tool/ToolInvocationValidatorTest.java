package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvocationValidatorTest {

    private final ToolInvocationValidator validator = new ToolInvocationValidator(
            new AgentToolRegistry(List.of(new EchoTool())), new ObjectMapper(), 160
    );
    private final ToolInvocationContext context = new ToolInvocationContext(
            "user:42", "550e8400-e29b-41d4-a716-446655440000",
            "trace-1", "call-1"
    );

    @Test
    void validArgumentsAreTypedHashedAndSanitizedBeforeExecution() {
        ValidatedToolInvocation<?, ?> invocation = validator.validate(
                context, "echoTool", "{\"message\":\"hello\",\"apiKey\":\"secret-value\"}"
        );

        assertThat(invocation.context()).isEqualTo(context);
        assertThat(invocation.metadata().name()).isEqualTo("echoTool");
        assertThat(invocation.input()).isEqualTo(new EchoRequest("hello", "secret-value"));
        assertThat(invocation.argumentsHash()).matches("[0-9a-f]{64}");
        assertThat(invocation.argumentsPreview()).contains("hello", "***").doesNotContain("secret-value");
    }

    @Test
    void hashUsesCanonicalJsonRatherThanModelPropertyOrder() {
        String first = validator.validate(context, "echoTool",
                "{\"message\":\"hello\",\"apiKey\":\"key\"}").argumentsHash();
        String reordered = validator.validate(context, "echoTool",
                "{\"apiKey\":\"key\",\"message\":\"hello\"}").argumentsHash();

        assertThat(first).isEqualTo(reordered);
    }

    @Test
    void unknownMalformedMissingAndWrongTypeArgumentsFailBeforeExecution() {
        assertThatThrownBy(() -> validator.validate(context, "missing", "{}"))
                .isInstanceOf(ToolInvocationValidationException.class)
                .hasMessageContaining("not registered");
        assertThatThrownBy(() -> validator.validate(context, "echoTool", "not-json"))
                .isInstanceOf(ToolInvocationValidationException.class)
                .hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> validator.validate(context, "echoTool", "{\"apiKey\":\"key\"}"))
                .isInstanceOf(ToolInvocationValidationException.class)
                .hasMessageContaining("message");
        assertThatThrownBy(() -> validator.validate(context, "echoTool",
                "{\"message\":7,\"apiKey\":\"key\"}"))
                .isInstanceOf(ToolInvocationValidationException.class)
                .hasMessageContaining("string");
    }

    @Test
    void invocationContextRejectsMissingSecurityAndTraceIdentity() {
        assertThatThrownBy(() -> new ToolInvocationContext("", context.runId(), "trace", "call"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerKey");
        assertThatThrownBy(() -> new ToolInvocationContext("user:42", "not-a-uuid", "trace", "call"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    private record EchoRequest(String message, String apiKey) {
    }

    private static final class EchoTool implements AgentTool<EchoRequest, ToolResult> {
        private static final ToolMetadata METADATA = new ToolMetadata(
                "echoTool", "1.0.0",
                "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"},\"apiKey\":{\"type\":\"string\"}},\"required\":[\"message\",\"apiKey\"],\"additionalProperties\":false}",
                ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER,
                Duration.ofSeconds(1), ToolRetryPolicy.none()
        );

        @Override
        public ToolMetadata metadata() {
            return METADATA;
        }

        @Override
        public Class<EchoRequest> inputType() {
            return EchoRequest.class;
        }

        @Override
        public ToolResult execute(EchoRequest input) {
            return ToolResult.success(input.message());
        }
    }
}
