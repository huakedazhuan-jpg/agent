package com.hkdzagent.agent.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class AdminAuditConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agent.audit", name = "repository", havingValue = "jdbc")
    public JdbcAdminAuditRepository jdbcAdminAuditRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        return new JdbcAdminAuditRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(AdminAuditRepository.class)
    public InMemoryAdminAuditRepository inMemoryAdminAuditRepository() {
        return new InMemoryAdminAuditRepository();
    }
}
