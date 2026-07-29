package com.hkdzagent.agent.ai;

import com.hkdzagent.agent.memory.JdbcChatMemory;
import com.hkdzagent.agent.memory.ConversationMessageRepository;
import com.hkdzagent.agent.memory.JdbcConversationMessageRepository;
import com.hkdzagent.agent.memory.ConversationSummaryRepository;
import com.hkdzagent.agent.memory.JdbcConversationSummaryRepository;
import com.hkdzagent.agent.memory.MemoryRepository;
import com.hkdzagent.agent.memory.JdbcMemoryRepository;
import com.hkdzagent.agent.memory.InMemoryMemoryRepository;
import com.hkdzagent.agent.memory.ChatMemoryConversationMessageRepository;
import com.hkdzagent.agent.memory.MemoryProcessingJobRepository;
import com.hkdzagent.agent.memory.JdbcMemoryProcessingJobRepository;
import com.hkdzagent.agent.memory.MemoryProcessingWorker;
import com.hkdzagent.agent.memory.MemoryExtractor;
import com.hkdzagent.agent.memory.MemorySanitizer;
import com.hkdzagent.agent.memory.ConversationSummarizer;
import com.hkdzagent.agent.context.TokenCounter;
import com.hkdzagent.agent.context.ConservativeTokenCounter;
import org.springframework.beans.factory.ObjectProvider;
import com.hkdzagent.agent.memory.PersistentChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class ChatMemoryConfig {

    @Bean
    @ConditionalOnMissingBean(MemorySanitizer.class)
    public MemorySanitizer memorySanitizer() {
        return new MemorySanitizer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public ChatMemory jdbcChatMemory(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcChatMemory(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public ConversationMessageRepository conversationMessageRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcConversationMessageRepository(
                jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public ConversationSummaryRepository conversationSummaryRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        return new JdbcConversationSummaryRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public MemoryRepository jdbcMemoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        return new JdbcMemoryRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryRepository.class)
    public MemoryRepository inMemoryMemoryRepository() {
        return new InMemoryMemoryRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public MemoryProcessingJobRepository memoryProcessingJobRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcMemoryProcessingJobRepository(
                jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public MemoryExtractor memoryExtractor(MemorySanitizer sanitizer) {
        return new MemoryExtractor(sanitizer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public ConversationSummarizer conversationSummarizer(
            ConversationSummaryRepository repository,
            ObjectProvider<TokenCounter> tokenCounter,
            MemorySanitizer sanitizer
    ) {
        return new ConversationSummarizer(
                repository, tokenCounter.getIfAvailable(ConservativeTokenCounter::new),
                prompt -> {
                    String safe = prompt.lines()
                            .filter(line -> !line.matches("^\\[\\d+\\]\\[TOOL].*"))
                            .filter(line -> sanitizer.sanitize(line).isPresent())
                            .reduce("", (left, right) -> left + right + "\n");
                    return safe.length() <= 6000
                            ? safe
                            : safe.substring(safe.length() - 6000);
                },
                3500);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory", name = "repository", havingValue = "jdbc")
    public MemoryProcessingWorker memoryProcessingWorker(
            MemoryProcessingJobRepository jobs,
            ConversationMessageRepository messages,
            ConversationSummarizer summarizer,
            MemoryExtractor extractor,
            MemoryRepository memories
    ) {
        return new MemoryProcessingWorker(jobs, messages, summarizer, extractor, memories);
    }

    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory(ChatMemoryProperties properties) {
        return new PersistentChatMemory(properties.file());
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMessageRepository.class)
    public ConversationMessageRepository localConversationMessageRepository(
            ChatMemory chatMemory
    ) {
        return new ChatMemoryConversationMessageRepository(chatMemory);
    }
}
