package com.hkdzagent.agent.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class LLMClient {

    private final ChatClient chatClient;
    private final KimiToolCallingClient kimiToolCallingClient;

    // Spring Boot 会自动注入按配置初始化好的 ChatClient.Builder。
    public LLMClient(ChatClient.Builder builder, ChatMemory chatMemory, KimiToolCallingClient kimiToolCallingClient) {
        this.kimiToolCallingClient = kimiToolCallingClient;
        this.chatClient = builder
                .defaultSystem("""
                        你是一个股票信息查询 agent，负责帮助用户查询股票、ETF、指数和上市公司公开信息。
                        优先使用 HTTP 工具查询公开行情数据，常用数据源包括 Yahoo Finance、Stooq 和常规搜索网站。
                        可使用 query1.finance.yahoo.com、query2.finance.yahoo.com 查询行情摘要或图表数据，可使用 stooq.com 查询补充行情。
                        回答时要注明数据来源和查询时间，说明行情数据可能延迟或缺失。
                        只做信息查询、整理和解释，不提供投资建议，不承诺收益，不替用户做买卖决策。
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 基础对话方法，非流式，一次性返回完整结果。
     */
    public String askSimple(String userMessage) {
        return this.chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * 流式对话方法，返回用于 SSE 的文本片段流。
     */
    public Flux<String> askStream(String userMessage) {
        return this.chatClient.prompt()
                .user(userMessage)
                .stream()
                .content();
    }

    /**
     * 智能体核心方法：带工具和会话记忆的对话。
     */
    public String askWithTools(String userMessage, String sessionId) {
        return kimiToolCallingClient.askWithTools(userMessage, sessionId);
    }
}





