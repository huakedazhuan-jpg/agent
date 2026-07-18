package com.hkdzagent.agent.ai;

import com.hkdzagent.agent.memory.PersistentChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(@Value("${agent.memory.file:data/chat-memory.jsonl}") String memoryFile) {
        return new PersistentChatMemory(Path.of(memoryFile));
    }
}
