package com.hkdzagent.agent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "agent.rag")
public class RagProperties {

    private Path indexFile = Path.of("data/rag-index.json");

    public Path indexFile() {
        return indexFile;
    }

    public Path getIndexFile() {
        return indexFile;
    }

    public void setIndexFile(Path indexFile) {
        this.indexFile = indexFile;
    }
}
