package com.hkdzagent.agent.context;

import com.hkdzagent.agent.memory.ConversationMessageRepository;
import com.hkdzagent.agent.memory.ConversationSummaryRepository;
import com.hkdzagent.agent.memory.MemoryRepository;
import com.hkdzagent.agent.memory.MemoryRetriever;
import com.hkdzagent.agent.memory.MemorySanitizer;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
@EnableConfigurationProperties(ContextBudget.class)
public class ContextConfiguration {

    @Bean
    @ConditionalOnMissingBean(MemorySanitizer.class)
    public MemorySanitizer memorySanitizer() {
        return new MemorySanitizer();
    }

    @Bean
    public MemoryRetriever memoryRetriever(
            MemoryRepository memoryRepository,
            MemorySanitizer sanitizer
    ) {
        return new MemoryRetriever(memoryRepository, sanitizer);
    }

    @Bean
    public ContextSource contextSource(
            ChatMemory chatMemory,
            ObjectProvider<ConversationMessageRepository> messages,
            ObjectProvider<ConversationSummaryRepository> summaries,
            MemoryRetriever memories
    ) {
        return new DefaultContextSource(
                chatMemory, messages.getIfAvailable(), summaries.getIfAvailable(), memories);
    }

    @Bean
    public ContextAssembler contextAssembler(
            ContextSource source,
            TokenCounter tokenCounter,
            ContextBudget budget
    ) {
        return new ContextAssembler(source, tokenCounter, budget);
    }
}
