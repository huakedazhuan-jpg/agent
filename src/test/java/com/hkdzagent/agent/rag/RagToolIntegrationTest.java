package com.hkdzagent.agent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class RagToolIntegrationTest {

    private static final String RAG_PACKAGE = "com.hkdzagent.agent.rag.";

    @TempDir
    Path tempDir;

    @Test
    void knowledgeSearchToolReturnsSearchResultsWithSources() throws Exception {
        Object knowledgeBase = newInstance("LocalKnowledgeBase", tempDir.resolve("tool-index.json"));
        invoke(knowledgeBase, "importDocument",
                "doc-aapl",
                "apple-capital-policy.md",
                "AAPL share repurchase authorization appears in this knowledge base document.");

        Object tool = newInstance("KnowledgeSearchTool", knowledgeBase);
        String output = String.valueOf(invoke(tool, "execute", "share repurchase"));

        assertThat(output)
                .contains("AAPL share repurchase")
                .contains("Sources:")
                .contains("apple-capital-policy.md#chunk-");
    }

    @Test
    void agentToolDefinitionsExposeKnowledgeSearchTool() throws Exception {
        String kimiClientSource = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/ai/KimiToolCallingClient.java"
        ));
        String toolRegistrySource = Files.readString(Path.of(
                "src/main/java/com/hkdzagent/agent/tool/ToolRegistryConfig.java"
        ));

        assertThat(toolRegistrySource).contains("knowledgeSearchTool");
        assertThat(kimiClientSource)
                .contains("knowledgeSearchTool")
                .contains("Search the local RAG knowledge base");
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
        String className = RAG_PACKAGE + simpleName;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("Expected " + className + " to exist.");
            return Object.class;
        }
    }
}
