package com.hkdzagent.agent.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuInboxConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeishuInboxConfig.class);

    @Test
    void configuresInMemoryInboxByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FeishuEventInboxRepository.class);
            assertThat(context.getBean(FeishuEventInboxRepository.class))
                    .isInstanceOf(InMemoryFeishuEventInboxRepository.class);
        });
    }

    @Test
    void configuresJdbcInboxWhenRequested() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:feishu_inbox_config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );

        contextRunner
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NamedParameterJdbcTemplate.class,
                        () -> new NamedParameterJdbcTemplate(dataSource))
                .withPropertyValues("feishu.inbox.repository=jdbc")
                .run(context -> {
                    assertThat(context).hasSingleBean(FeishuEventInboxRepository.class);
                    assertThat(context.getBean(FeishuEventInboxRepository.class))
                            .isInstanceOf(JdbcFeishuEventInboxRepository.class);
                });
    }
}
