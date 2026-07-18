package com.hkdzagent.agent.ai;

import com.hkdzagent.agent.memory.PersistentChatMemory;
import com.hkdzagent.agent.memory.JdbcChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void configuresFileBackedPersistentChatMemoryBeanByDefault() {
        Path storageFile = tempDir.resolve("configured-memory.jsonl");

        new ApplicationContextRunner()
                .withUserConfiguration(ChatMemoryConfig.class)
                .withPropertyValues("agent.memory.file=" + storageFile)
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatMemory.class);

                    ChatMemory chatMemory = context.getBean(ChatMemory.class);
                    assertThat(chatMemory).isInstanceOf(PersistentChatMemory.class);

                    chatMemory.add("session-config", List.of(new UserMessage("persist through config")));

                    ChatMemory reloadedMemory = new PersistentChatMemory(storageFile);
                    assertThat(reloadedMemory.get("session-config"))
                            .extracting(Message::getText)
                            .containsExactly("persist through config");
                });
    }

    @Test
    void configuresJdbcChatMemoryBeanWhenRequested() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:chat_memory_config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );

        new ApplicationContextRunner()
                .withUserConfiguration(ChatMemoryConfig.class)
                .withBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withPropertyValues("agent.memory.repository=jdbc")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatMemory.class);
                    assertThat(context.getBean(ChatMemory.class)).isInstanceOf(JdbcChatMemory.class);
                });
    }
}
