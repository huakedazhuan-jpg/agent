package com.hkdzagent.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class AgentTraceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agent.trace", name = "repository", havingValue = "jdbc")
    public JdbcAgentTraceRepository jdbcAgentTraceRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new JdbcAgentTraceRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(AgentTraceRepository.class)
    public InMemoryAgentTraceRepository inMemoryAgentTraceRepository() {
        return new InMemoryAgentTraceRepository();
    }

    @Bean
    public AgentTraceSanitizer agentTraceSanitizer() {
        return new AgentTraceSanitizer(120);
    }

    @Bean
    public AgentTraceRecorder agentTraceRecorder(
            AgentTraceRepository repository,
            AgentTraceSanitizer sanitizer
    ) {
        return new AgentTraceRecorder(repository, sanitizer);
    }
}
