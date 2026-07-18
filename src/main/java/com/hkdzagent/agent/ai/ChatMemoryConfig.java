package com.hkdzagent.agent.ai;

import com.hkdzagent.agent.memory.PersistentChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryProperties properties) {
        return new PersistentChatMemory(properties.file());
    }
}
