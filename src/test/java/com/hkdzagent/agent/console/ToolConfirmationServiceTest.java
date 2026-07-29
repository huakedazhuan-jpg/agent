package com.hkdzagent.agent.console;

import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.AgentTool;
import com.hkdzagent.agent.tool.ToolApprovalPolicy;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import com.hkdzagent.agent.tool.ToolMetadata;
import com.hkdzagent.agent.tool.ToolResult;
import com.hkdzagent.agent.tool.ToolRetryPolicy;
import com.hkdzagent.agent.tool.ToolRiskLevel;
import com.hkdzagent.agent.tool.ValidatedToolInvocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class ToolConfirmationServiceTest {

    private static final String CONSOLE_PACKAGE = "com.hkdzagent.agent.console.";

    @Test
    void createsPendingConfirmationForSensitiveToolCallsAndAllowsApproval() throws Exception {
        Object service = newInstance("ToolConfirmationService");

        Object pending = invoke(service, "requestConfirmation",
                "console-session",
                "trace-confirm",
                "commandExecuteTool",
                "{\"command\":\"mvn test\"}");

        String confirmationId = (String) invoke(pending, "id");
        assertThat(confirmationId).isNotBlank();
        assertThat(invoke(pending, "sessionId")).isEqualTo("console-session");
        assertThat(invoke(pending, "traceId")).isEqualTo("trace-confirm");
        assertThat(invoke(pending, "toolName")).isEqualTo("commandExecuteTool");
        assertThat(invoke(pending, "status").toString()).isEqualTo("PENDING");
        assertThat(invoke(pending, "expiresAt")).isNotNull();

        List<?> pendingItems = (List<?>) invoke(
                service,
                "findPendingBySessionId",
                ActorIdentity.localAnonymous(),
                "console-session"
        );
        assertThat(pendingItems).hasSize(1);
        assertThat(invoke(pendingItems.get(0), "id")).isEqualTo(confirmationId);

        Object approved = invoke(service, "approve", confirmationId);
        assertThat(invoke(approved, "status").toString()).isEqualTo("APPROVED");
        assertThat((List<?>) invoke(
                service,
                "findPendingBySessionId",
                ActorIdentity.localAnonymous(),
                "console-session"
        )).isEmpty();

        assertThatThrownBy(() -> invoke(service, "reject", confirmationId, "late rejection"))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("confirmation is no longer pending: " + confirmationId + " (APPROVED)");
    }

    @Test
    void rejectsPendingConfirmationWithoutExposingRawSecrets() throws Exception {
        Object service = newInstance("ToolConfirmationService");

        Object pending = invoke(service, "requestConfirmation",
                "console-session",
                "trace-secret",
                "fileOperationTool",
                "{\"filePath\":\"notes.txt\",\"content\":\"api_key=secret-value\"}");

        String serialized = pending.toString().toLowerCase();
        assertThat(serialized).doesNotContain("secret-value");
        assertThat(serialized).contains("[redacted]");

        Object rejected = invoke(service, "reject", invoke(pending, "id"), "user cancelled");
        assertThat(invoke(rejected, "status").toString()).isEqualTo("REJECTED");
        assertThat(invoke(rejected, "decisionReason")).isEqualTo("user cancelled");
    }

    @Test
    void boundApprovalRequiresTheOwnerAndExactValidatedArgumentsHash() {
        ToolConfirmationService service = new ToolConfirmationService();
        ActorIdentity owner = ActorIdentity.user("owner-a");
        ValidatedToolInvocation<Input, ToolResult> invocation = invocation(owner, "a".repeat(64));

        ToolConfirmation pending = service.requestConfirmationForInvocation("session-1", invocation);

        assertThat(pending.ownerKey()).isEqualTo(owner.key());
        assertThat(pending.runId()).isEqualTo(invocation.context().runId());
        assertThat(pending.toolCallId()).isEqualTo("call-1");
        assertThat(pending.toolVersion()).isEqualTo("2.1.0");
        assertThat(pending.argumentsHash()).isEqualTo(invocation.argumentsHash());

        assertThatThrownBy(() -> service.approve(
                ActorIdentity.user("owner-b"), pending.id(), invocation.argumentsHash()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("owner");
        assertThatThrownBy(() -> service.approve(owner, pending.id(), "b".repeat(64)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("hash");

        ToolConfirmation approved = service.approve(owner, pending.id(), invocation.argumentsHash());
        assertThat(approved.status()).isEqualTo(ToolConfirmation.Status.APPROVED);
        assertThatThrownBy(() -> service.approve(owner, pending.id(), invocation.argumentsHash()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer pending");
    }

    @Test
    void cancelledPendingConfirmationCannotBeApprovedLater() {
        ToolConfirmationService service = new ToolConfirmationService();
        ToolConfirmation pending = service.requestConfirmation(
                "session-cancel", "trace-cancel", "commandExecuteTool", "{}");

        ToolConfirmation cancelled = service.cancelPending(
                pending.id(), "agent run cancelled by owner");

        assertThat(cancelled.status()).isEqualTo(ToolConfirmation.Status.CANCELLED);
        assertThat(cancelled.decisionReason()).isEqualTo("agent run cancelled by owner");
        assertThatThrownBy(() -> service.approve(pending.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    private ValidatedToolInvocation<Input, ToolResult> invocation(ActorIdentity owner, String hash) {
        AgentTool<Input, ToolResult> tool = new AgentTool<>() {
            private final ToolMetadata metadata = new ToolMetadata(
                    "dangerousTool", "2.1.0", "{\"type\":\"object\"}",
                    ToolRiskLevel.HIGH, ToolApprovalPolicy.ALWAYS,
                    Duration.ofSeconds(5), ToolRetryPolicy.none());

            @Override
            public ToolMetadata metadata() {
                return metadata;
            }

            @Override
            public Class<Input> inputType() {
                return Input.class;
            }

            @Override
            public ToolResult execute(Input input) {
                return ToolResult.success(input.value());
            }
        };
        return new ValidatedToolInvocation<>(
                new ToolInvocationContext(
                        owner.key(), "550e8400-e29b-41d4-a716-446655440000",
                        "trace-bound", "call-1", Set.of("ROLE_USER")),
                tool, tool.metadata(), new Input("sensitive"), hash,
                "{\"value\":\"[redacted]\"}"
        );
    }

    private record Input(String value) {
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
        String className = CONSOLE_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }
}
