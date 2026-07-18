package com.hkdzagent.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class LocalKnowledgeBase {

    private static final int MAX_CHUNK_CHARS = 500;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "by", "does", "for", "in", "is", "it",
            "of", "on", "or", "the", "this", "to", "what", "with"
    );

    private final Path indexFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<KnowledgeChunk> chunks = new ArrayList<>();

    public LocalKnowledgeBase(Path indexFile) {
        this.indexFile = indexFile;
        load();
    }

    public synchronized KnowledgeImportResult importDocument(String documentId, String sourceName, String content) {
        chunks.removeIf(chunk -> chunk.documentId().equals(documentId));
        List<String> chunkContents = chunk(content == null ? "" : content);
        for (int i = 0; i < chunkContents.size(); i++) {
            chunks.add(new KnowledgeChunk(documentId, sourceName, i, chunkContents.get(i)));
        }
        save();
        return new KnowledgeImportResult(documentId, chunkContents.size());
    }

    public synchronized List<KnowledgeSearchResult> search(String query, int topK) {
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        return chunks.stream()
                .map(chunk -> result(chunk, score(queryTerms, chunk.content())))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                .limit(Math.max(0, topK))
                .toList();
    }

    public synchronized RagAnswer answer(String question, int topK) {
        List<KnowledgeSearchResult> results = search(question, topK);
        if (results.isEmpty()) {
            return new RagAnswer("No relevant knowledge base content found.", List.of());
        }

        StringBuilder answer = new StringBuilder();
        for (KnowledgeSearchResult result : results) {
            if (!answer.isEmpty()) {
                answer.append("\n");
            }
            answer.append(result.content());
        }

        return new RagAnswer(answer.toString(), results.stream()
                .map(KnowledgeSearchResult::sourceRef)
                .distinct()
                .toList());
    }

    private KnowledgeSearchResult result(KnowledgeChunk chunk, double score) {
        String sourceRef = chunk.sourceName() + "#chunk-" + chunk.chunkIndex();
        return new KnowledgeSearchResult(
                chunk.documentId(),
                chunk.sourceName(),
                chunk.chunkIndex(),
                sourceRef,
                chunk.content(),
                score
        );
    }

    private double score(Set<String> queryTerms, String content) {
        Set<String> contentTerms = tokenize(content);
        double score = 0;
        for (String queryTerm : queryTerms) {
            if (contentTerms.contains(queryTerm)) {
                score += 1;
            }
        }
        String normalizedContent = normalize(content);
        String normalizedQuery = String.join(" ", queryTerms);
        if (!normalizedQuery.isBlank() && normalizedContent.contains(normalizedQuery)) {
            score += queryTerms.size();
        }
        return score;
    }

    private List<String> chunk(String content) {
        List<String> result = new ArrayList<>();
        String normalized = content.strip();
        while (normalized.length() > MAX_CHUNK_CHARS) {
            int splitAt = splitPosition(normalized);
            result.add(normalized.substring(0, splitAt).strip());
            normalized = normalized.substring(splitAt).strip();
        }
        if (!normalized.isBlank()) {
            result.add(normalized);
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    private int splitPosition(String content) {
        int splitAt = content.lastIndexOf('\n', MAX_CHUNK_CHARS);
        if (splitAt < MAX_CHUNK_CHARS / 2) {
            splitAt = content.lastIndexOf('.', MAX_CHUNK_CHARS);
        }
        if (splitAt < MAX_CHUNK_CHARS / 2) {
            splitAt = content.lastIndexOf(' ', MAX_CHUNK_CHARS);
        }
        return splitAt < MAX_CHUNK_CHARS / 2 ? MAX_CHUNK_CHARS : splitAt + 1;
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_PATTERN.split(normalize(value))) {
            if (!token.isBlank() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (!Files.exists(indexFile)) {
            return;
        }
        try {
            chunks.clear();
            chunks.addAll(objectMapper.readValue(
                    Files.readString(indexFile, StandardCharsets.UTF_8),
                    new TypeReference<List<KnowledgeChunk>>() {
                    }
            ));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load RAG index from " + indexFile, e);
        }
    }

    private void save() {
        try {
            Path parent = indexFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(indexFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunks),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save RAG index to " + indexFile, e);
        }
    }

    private record KnowledgeChunk(String documentId, String sourceName, int chunkIndex, String content) {
    }
}
