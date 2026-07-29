package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolMetadataTest {

    @Test
    void metadataCarriesThePolicyInformationRequiredByTheExecutionPipeline() {
        ToolMetadata metadata = new ToolMetadata(
                "marketSearch", "1.0.0", "{\"type\":\"object\"}",
                ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER,
                Duration.ofSeconds(5), ToolRetryPolicy.fixed(2, Duration.ofMillis(100))
        );

        assertThat(metadata.name()).isEqualTo("marketSearch");
        assertThat(metadata.version()).isEqualTo("1.0.0");
        assertThat(metadata.inputSchema()).contains("object");
        assertThat(metadata.riskLevel()).isEqualTo(ToolRiskLevel.LOW);
        assertThat(metadata.approvalPolicy()).isEqualTo(ToolApprovalPolicy.NEVER);
        assertThat(metadata.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(metadata.retryPolicy().maxAttempts()).isEqualTo(2);
    }

    @Test
    void invalidOrUnboundedMetadataIsRejectedAtRegistrationTime() {
        assertThatThrownBy(() -> new ToolMetadata(
                "shell", "1.0.0", "{}", ToolRiskLevel.CRITICAL,
                ToolApprovalPolicy.ALWAYS, Duration.ZERO, ToolRetryPolicy.none()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");

        assertThatThrownBy(() -> ToolRetryPolicy.fixed(0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void builtInToolsDeclareStableNamesSchemasAndRiskPolicies() throws Exception {
        ToolSecurityProperties properties = new ToolSecurityProperties();
        ToolPermissionService permissions = new ToolPermissionService(properties);

        assertMetadata(new WorkspaceFileTool(permissions).metadata(),
                "fileOperationTool", ToolRiskLevel.HIGH, ToolApprovalPolicy.ALWAYS);
        assertMetadata(new CommandExecuteTool(permissions).metadata(),
                "commandExecuteTool", ToolRiskLevel.CRITICAL, ToolApprovalPolicy.ALWAYS);
        assertMetadata(new HttpRequestTool(permissions).metadata(),
                "httpRequestTool", ToolRiskLevel.MEDIUM, ToolApprovalPolicy.CONDITIONAL);
        assertMetadata(new StockQuoteTool(
                        new MarketDataProperties(),
                        new com.fasterxml.jackson.databind.ObjectMapper()).metadata(),
                "stockQuoteTool", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER);
        assertMetadata(new WebSearchTool("test-key").metadata(),
                "webSearchTool", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER);
        assertMetadata(new KnowledgeSearchAgentTool(
                        new com.hkdzagent.agent.rag.KnowledgeSearchTool(
                                new com.hkdzagent.agent.rag.LocalKnowledgeBase(
                                        java.nio.file.Path.of("data/rag-index.json")))).metadata(),
                "knowledgeSearchTool", ToolRiskLevel.LOW, ToolApprovalPolicy.NEVER);
    }

    private void assertMetadata(
            ToolMetadata metadata,
            String name,
            ToolRiskLevel riskLevel,
            ToolApprovalPolicy approvalPolicy
    ) {
        assertThat(metadata.name()).isEqualTo(name);
        assertThat(metadata.version()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(metadata.inputSchema()).contains("\"type\":\"object\"");
        assertThat(metadata.riskLevel()).isEqualTo(riskLevel);
        assertThat(metadata.approvalPolicy()).isEqualTo(approvalPolicy);
        assertThat(metadata.timeout()).isPositive();
        assertThat(metadata.retryPolicy()).isNotNull();
    }
}
