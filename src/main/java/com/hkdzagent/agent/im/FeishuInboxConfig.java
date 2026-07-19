package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class FeishuInboxConfig {

    @Bean
    @ConditionalOnProperty(prefix = "feishu.inbox", name = "repository", havingValue = "jdbc")
    public JdbcFeishuEventInboxRepository jdbcFeishuEventInboxRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new JdbcFeishuEventInboxRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(FeishuEventInboxRepository.class)
    public InMemoryFeishuEventInboxRepository inMemoryFeishuEventInboxRepository() {
        return new InMemoryFeishuEventInboxRepository();
    }
}
