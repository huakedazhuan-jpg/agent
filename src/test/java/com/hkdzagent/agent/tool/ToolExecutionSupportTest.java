package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ToolExecutionSupportTest {

    private static final String TOOL_PACKAGE = "com.hkdzagent.agent.tool.";

    @Test
    void toolResultProvidesUnifiedStatusesAndMessages() throws Exception {
        Class<?> resultClass = loadClass("ToolResult");

        Object success = invokeStatic(resultClass, "success", "operation completed");
        Object rejected = invokeStatic(resultClass, "rejected", "command is not allowed");
        Object failure = invokeStatic(resultClass, "failure", "network request failed: boom");

        assertThat(invoke(success, "status").toString()).isEqualTo("SUCCESS");
        assertThat(invoke(success, "message")).isEqualTo("operation completed");
        assertThat(invoke(rejected, "status").toString()).isEqualTo("REJECTED");
        assertThat(invoke(rejected, "message")).isEqualTo("rejected: command is not allowed");
        assertThat(invoke(failure, "status").toString()).isEqualTo("FAILED");
        assertThat(invoke(failure, "message")).isEqualTo("network request failed: boom");
    }

    @Test
    void executionSupportReturnsResultMessagesAndCatchesUnexpectedFailures() throws Exception {
        Class<?> resultClass = loadClass("ToolResult");
        Object support = newSupport();
        Method execute = support.getClass().getMethod("execute", String.class, Callable.class, String.class);

        String success = (String) execute.invoke(support, "testTool",
                (Callable<Object>) () -> invokeStatic(resultClass, "success", "ok"),
                "test failed");
        String rejected = (String) execute.invoke(support, "testTool",
                (Callable<Object>) () -> invokeStatic(resultClass, "rejected", "not allowed"),
                "test failed");
        String failed = (String) execute.invoke(support, "testTool",
                (Callable<Object>) () -> {
                    throw new IllegalStateException("boom");
                },
                "test failed");

        assertThat(success).isEqualTo("ok");
        assertThat(rejected).isEqualTo("rejected: not allowed");
        assertThat(failed).isEqualTo("test failed: boom");
    }

    @Test
    void executionSupportPreservesInterruptedStatus() throws Exception {
        Object support = newSupport();
        Method execute = support.getClass().getMethod("execute", String.class, Callable.class, String.class);

        String result = (String) execute.invoke(support, "testTool",
                (Callable<Object>) () -> {
                    throw new InterruptedException("stop");
                },
                "test failed");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(result).isEqualTo("test failed: stop");
    }

    private static Object invokeStatic(Class<?> targetClass, String methodName, String value) throws Exception {
        return targetClass.getMethod(methodName, String.class).invoke(null, value);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static Object newSupport() throws Exception {
        Class<?> supportClass = loadClass("ToolExecutionSupport");
        Constructor<?> constructor = supportClass.getDeclaredConstructor(Logger.class);
        constructor.setAccessible(true);
        return constructor.newInstance(NOPLogger.NOP_LOGGER);
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
