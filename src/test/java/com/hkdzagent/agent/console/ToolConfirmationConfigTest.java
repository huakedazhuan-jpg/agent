package com.hkdzagent.agent.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ToolConfirmationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ToolConfirmationConfig.class)
            .withBean(AgentTraceSanitizer.class, () -> new AgentTraceSanitizer(120))
            .withBean(ToolConfirmationProperties.class, ToolConfirmationProperties::new);

    @Test
    void configuresInMemoryRepositoryByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ToolConfirmationRepository.class);
            assertThat(context.getBean(ToolConfirmationRepository.class))
                    .isInstanceOf(InMemoryToolConfirmationRepository.class);
            assertThat(context).hasSingleBean(ToolConfirmationService.class);
        });
    }

    @Test
    void configuresJdbcRepositoryWhenRequested() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tool_approval_config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );

        contextRunner
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NamedParameterJdbcTemplate.class,
                        () -> new NamedParameterJdbcTemplate(dataSource))
                .withPropertyValues("agent.tool-approval.repository=jdbc")
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolConfirmationRepository.class);
                    assertThat(context.getBean(ToolConfirmationRepository.class))
                            .isInstanceOf(JdbcToolConfirmationRepository.class);
                });
    }
}
