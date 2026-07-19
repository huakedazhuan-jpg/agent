package com.hkdzagent.agent.console;

import com.hkdzagent.agent.security.ActorIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
