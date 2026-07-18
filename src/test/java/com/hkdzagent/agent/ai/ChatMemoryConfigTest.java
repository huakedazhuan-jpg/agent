package com.hkdzagent.agent.ai;

import com.hkdzagent.agent.memory.PersistentChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void configuresPersistentChatMemoryBean() {
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
                    assertThat(reloadedMemory.get("session-config", 10))
                            .extracting(Message::getContent)
                            .containsExactly("persist through config");
                });
    }
}
