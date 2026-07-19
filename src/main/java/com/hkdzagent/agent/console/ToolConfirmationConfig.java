package com.hkdzagent.agent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Clock;

@Configuration
public class ToolConfirmationConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agent.tool-approval", name = "repository", havingValue = "jdbc")
    public JdbcToolConfirmationRepository jdbcToolConfirmationRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new JdbcToolConfirmationRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ToolConfirmationRepository.class)
    public InMemoryToolConfirmationRepository inMemoryToolConfirmationRepository() {
        return new InMemoryToolConfirmationRepository();
    }

    @Bean
    public ToolConfirmationService toolConfirmationService(
            ToolConfirmationRepository repository,
            AgentTraceSanitizer sanitizer,
            ToolConfirmationProperties properties
    ) {
        return new ToolConfirmationService(repository, sanitizer, properties.ttl(), Clock.systemUTC());
    }
}
