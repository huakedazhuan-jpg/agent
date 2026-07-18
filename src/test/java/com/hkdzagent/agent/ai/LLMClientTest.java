package com.hkdzagent.agent.ai;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LLMClientTest {

    private static final Path LLM_CLIENT_SOURCE = Path.of("src/main/java/com/hkdzagent/agent/ai/LLMClient.java");

    @Test
    void askWithToolsDelegatesToCustomKimiToolCallingClient() throws Exception {
        String source = Files.readString(LLM_CLIENT_SOURCE);
        String methodSource = askWithToolsSource(source);

        assertThat(source).contains("KimiToolCallingClient");
        assertThat(methodSource).contains("kimiToolCallingClient.askWithTools(userMessage, sessionId)");
        assertThat(methodSource).doesNotContain(".functions(");
    }

    @Test
    void systemPromptDefinesStockInformationAgentBehavior() throws Exception {
        String source = Files.readString(LLM_CLIENT_SOURCE);

        assertThat(source).contains("股票信息查询 agent");
        assertThat(source).contains("Yahoo Finance");
        assertThat(source).contains("Stooq");
        assertThat(source).contains("query1.finance.yahoo.com");
        assertThat(source).contains("query2.finance.yahoo.com");
        assertThat(source).contains("stooq.com");
        assertThat(source).contains("注明数据来源");
        assertThat(source).contains("不提供投资建议");
    }

    private static String askWithToolsSource(String source) {
        int methodStart = source.indexOf("String askWithTools");
        assertThat(methodStart).isGreaterThanOrEqualTo(0);

        int methodEnd = source.indexOf("\n    }", methodStart);
        assertThat(methodEnd).isGreaterThan(methodStart);

        return source.substring(methodStart, methodEnd);
    }
}
