package com.hkdzagent.agent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class RagKnowledgeBaseTest {

    private static final String RAG_PACKAGE = "com.hkdzagent.agent.rag.";

    @TempDir
    Path tempDir;

    @Test
    void importsChunksPersistsAndSearchesWithSourceReferences() throws Exception {
        Path indexFile = tempDir.resolve("rag-index.json");
        Object knowledgeBase = newInstance("LocalKnowledgeBase", indexFile);

        Object importResult = invoke(knowledgeBase, "importDocument",
                "doc-aapl",
                "apple-capital-policy.md",
                repeated("""
                        AAPL announced a share repurchase authorization.
                        The capital return policy includes buybacks and dividends.
                        Source material explains that repurchase plans can change over time.
                        """, 30));
        invoke(knowledgeBase, "importDocument",
                "doc-cloud",
                "cloud-market.md",
                repeated("""
                        Cloud infrastructure revenue depends on compute, storage, and network demand.
                        Enterprise migration affects cloud platform growth.
                        """, 30));

        assertThat(invoke(importResult, "documentId")).isEqualTo("doc-aapl");
        assertThat((Integer) invoke(importResult, "chunkCount")).isGreaterThan(1);
        assertThat(Files.exists(indexFile)).isTrue();

        List<?> results = search(knowledgeBase, "share repurchase authorization", 3);

        assertThat(results).isNotEmpty();
        Object best = results.get(0);
        assertThat(invoke(best, "documentId")).isEqualTo("doc-aapl");
        assertThat(invoke(best, "sourceName")).isEqualTo("apple-capital-policy.md");
        assertThat((Integer) invoke(best, "chunkIndex")).isGreaterThanOrEqualTo(0);
        assertThat(String.valueOf(invoke(best, "sourceRef"))).contains("apple-capital-policy.md#chunk-");
        assertThat(String.valueOf(invoke(best, "content"))).contains("share repurchase");

        Object reloadedKnowledgeBase = newInstance("LocalKnowledgeBase", indexFile);
        List<?> reloadedResults = search(reloadedKnowledgeBase, "capital return buybacks", 2);

        assertThat(reloadedResults).isNotEmpty();
        assertThat(invoke(reloadedResults.get(0), "documentId")).isEqualTo("doc-aapl");
    }

    @Test
    void answersQuestionsWithSourceList() throws Exception {
        Object knowledgeBase = newInstance("LocalKnowledgeBase", tempDir.resolve("answer-index.json"));
        invoke(knowledgeBase, "importDocument",
                "doc-aapl",
                "apple-capital-policy.md",
                """
                        AAPL capital return policy includes share repurchase authorization and dividends.
                        The source states that buyback plans depend on board approval and market conditions.
                        """);

        Object answer = invoke(knowledgeBase, "answer",
                "What does the capital return policy include?",
                2);

        assertThat(String.valueOf(invoke(answer, "answer")))
                .contains("share repurchase")
                .contains("dividends");
        List<?> sources = (List<?>) invoke(answer, "sources");
        assertThat(sources).isNotEmpty();
        assertThat(String.valueOf(sources.get(0))).contains("apple-capital-policy.md#chunk-");
    }

    @SuppressWarnings("unchecked")
    private static List<?> search(Object knowledgeBase, String query, int topK) throws Exception {
        return (List<?>) invoke(knowledgeBase, "search", query, topK);
    }

    private static String repeated(String value, int times) {
        return value.repeat(times);
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
