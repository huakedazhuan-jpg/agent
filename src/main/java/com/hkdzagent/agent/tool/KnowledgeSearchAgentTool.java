package com.hkdzagent.agent.tool;

import com.hkdzagent.agent.rag.KnowledgeSearchTool;

import java.time.Duration;

/**
 * Adapts the local RAG search implementation to the authoritative agent-tool contract.
 */
public class KnowledgeSearchAgentTool implements AgentTool<KnowledgeSearchRequest, ToolResult> {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "knowledgeSearchTool",
            "1.0.0",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}",
            ToolRiskLevel.LOW,
            ToolApprovalPolicy.NEVER,
            Duration.ofSeconds(5),
            ToolRetryPolicy.none()
    );

    private final KnowledgeSearchTool delegate;

    public KnowledgeSearchAgentTool(KnowledgeSearchTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public Class<KnowledgeSearchRequest> inputType() {
        return KnowledgeSearchRequest.class;
    }

    @Override
    public ToolResult execute(KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ToolResult.rejected("query must not be blank");
        }
        return ToolResult.success(delegate.execute(request.query()));
    }
}
