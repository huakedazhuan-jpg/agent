package com.hkdzagent.agent.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuditConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdminAuditConfig.class);

    @Test
    void usesInMemoryRepositoryByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AdminAuditRepository.class);
            assertThat(context.getBean(AdminAuditRepository.class))
                    .isInstanceOf(InMemoryAdminAuditRepository.class);
        });
    }

    @Test
    void usesJdbcRepositoryWhenConfigured() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_audit_config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        contextRunner
                .withBean(NamedParameterJdbcTemplate.class,
                        () -> new NamedParameterJdbcTemplate(dataSource))
                .withPropertyValues("agent.audit.repository=jdbc")
                .run(context -> assertThat(context.getBean(AdminAuditRepository.class))
                        .isInstanceOf(JdbcAdminAuditRepository.class));
    }
}
