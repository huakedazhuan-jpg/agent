package com.hkdzagent.agent.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "agent.memory")
public class ChatMemoryProperties {

    private String repository = "file";
    private Path file = Path.of("data/chat-memory.jsonl");

    public String repository() {
        return repository;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

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
