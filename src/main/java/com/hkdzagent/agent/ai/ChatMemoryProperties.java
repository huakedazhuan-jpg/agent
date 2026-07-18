package com.hkdzagent.agent.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "agent.memory")
public class ChatMemoryProperties {

    private Path file = Path.of("data/chat-memory.jsonl");

    public Path file() {
        return file;
    }

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }
}
