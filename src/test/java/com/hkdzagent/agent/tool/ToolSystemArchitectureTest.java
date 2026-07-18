package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ToolSystemArchitectureTest {

    private static final String TOOL_PACKAGE = "com.hkdzagent.agent.tool.";

    @Test
    void eachToolAndSharedConcernHasItsOwnClass() {
        assertClassExists("ToolResult");
        assertClassExists("ToolExecutionSupport");
        assertClassExists("ToolPermissionService");
        assertClassExists("WorkspaceFileTool");
        assertClassExists("CommandExecuteTool");
        assertClassExists("HttpRequestTool");
        assertClassExists("WebSearchTool");
    }

    @Test
    void toolClassesReturnUnifiedToolResult() {
        Class<?> toolResultClass = loadClass("ToolResult");

        assertExecuteReturnsToolResult("WorkspaceFileTool", toolResultClass);
        assertExecuteReturnsToolResult("CommandExecuteTool", toolResultClass);
        assertExecuteReturnsToolResult("HttpRequestTool", toolResultClass);
        assertExecuteReturnsToolResult("WebSearchTool", toolResultClass);
    }

    @Test
    void registryKeepsCompatibleFunctionBeans() throws Exception {
        assertFunctionBean("fileOperationTool");
        assertFunctionBean("commandExecuteTool");
        assertFunctionBean("httpRequestTool");
        assertFunctionBean("webSearchTool");
    }

    @Test
    void registryDoesNotOwnBusinessHelperMethods() {
        List<String> privateMethods = Arrays.stream(ToolRegistryConfig.class.getDeclaredMethods())
                .filter(method -> Modifier.isPrivate(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .toList();

        assertThat(privateMethods)
                .as("ToolRegistryConfig should only register beans; tool logic belongs in independent classes")
                .isEmpty();
    }

    private static void assertClassExists(String simpleName) {
        loadClass(simpleName);
    }

    private static void assertExecuteReturnsToolResult(String toolClassName, Class<?> toolResultClass) {
        Class<?> toolClass = loadClass(toolClassName);
        List<Method> executeMethods = Arrays.stream(toolClass.getDeclaredMethods())
                .filter(method -> method.getName().equals("execute"))
                .toList();

        assertThat(executeMethods)
                .as(toolClassName + " should expose an execute method")
                .hasSize(1);
        assertThat(executeMethods.get(0).getReturnType()).isEqualTo(toolResultClass);
    }

    private static void assertFunctionBean(String methodName) throws Exception {
        Method method = ToolRegistryConfig.class.getMethod(methodName);
        assertThat(method.getReturnType()).isEqualTo(Function.class);
    }

    private static Class<?> loadClass(String simpleName) {
        String className = TOOL_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }
}
