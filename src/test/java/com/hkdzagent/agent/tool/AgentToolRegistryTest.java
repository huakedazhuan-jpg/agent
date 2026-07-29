package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    @Test
    void toolsAreResolvedOnlyThroughTheirRegisteredStableName() {
        StubTool search = new StubTool(metadata("search", ToolRiskLevel.LOW));
        StubTool write = new StubTool(metadata("writeFile", ToolRiskLevel.HIGH));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(search, write));

        assertThat(registry.require("search")).isSameAs(search);
        assertThat(registry.require("writeFile")).isSameAs(write);
        assertThat(registry.names()).containsExactly("search", "writeFile");
        assertThat(registry.metadata()).extracting(ToolMetadata::name)
                .containsExactly("search", "writeFile");
    }

    @Test
    void duplicateToolNamesFailFastInsteadOfSilentlyOverridingPolicy() {
        StubTool first = new StubTool(metadata("search", ToolRiskLevel.LOW));
        StubTool conflicting = new StubTool(metadata("search", ToolRiskLevel.CRITICAL));

        assertThatThrownBy(() -> new AgentToolRegistry(List.of(first, conflicting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("search");
    }

    @Test
    void unknownToolsAndMutableRegistryViewsAreRejected() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                new StubTool(metadata("search", ToolRiskLevel.LOW))
        ));

        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
        assertThatThrownBy(() -> registry.names().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.metadata().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ToolMetadata metadata(String name, ToolRiskLevel riskLevel) {
        return new ToolMetadata(
                name, "1.0.0", "{\"type\":\"object\"}", riskLevel,
                riskLevel == ToolRiskLevel.LOW ? ToolApprovalPolicy.NEVER : ToolApprovalPolicy.ALWAYS,
                Duration.ofSeconds(5), ToolRetryPolicy.none()
        );
    }

    private record StubTool(ToolMetadata metadata) implements AgentTool<String, ToolResult> {
        @Override
        public Class<String> inputType() {
            return String.class;
        }

        @Override
        public ToolResult execute(String input) {
            return ToolResult.success(input);
        }
    }
}
