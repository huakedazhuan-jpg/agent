package com.hkdzagent.agent.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ToolExecutionJournalConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agent.runtime", name = "repository", havingValue = "jdbc")
    public JdbcToolExecutionJournalRepository jdbcToolExecutionJournalRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcToolExecutionJournalRepository(
                jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean(ToolExecutionJournalRepository.class)
    public InMemoryToolExecutionJournalRepository inMemoryToolExecutionJournalRepository() {
        return new InMemoryToolExecutionJournalRepository();
    }
}
