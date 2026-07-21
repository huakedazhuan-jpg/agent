package com.hkdzagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ToolPipelineSpringWiringTest {

    @Autowired
    private AgentToolRegistry registry;

    @Autowired
    private ToolInvocationValidator validator;

    @Autowired
    private ToolPolicyEngine policyEngine;

    @Autowired
    private ToolExecutionPipeline pipeline;

    @Test
    void productionContextExposesOneValidatedPolicyControlledPipeline() {
        assertThat(registry.names()).containsExactly(
                "fileOperationTool",
                "commandExecuteTool",
                "httpRequestTool",
                "webSearchTool"
        );
        assertThat(validator).isNotNull();
        assertThat(policyEngine).isNotNull();
        assertThat(pipeline).isNotNull();
    }

    @Test
    void alwaysApprovalToolCannotExecuteThroughTheProductionPipelineBeforeApproval() {
        ToolPipelineResult result = pipeline.invoke(
                new ToolInvocationContext(
                        "user:phase-5",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "phase-5-trace",
                        "phase-5-call",
                        Set.of("ROLE_USER")
                ),
                "commandExecuteTool",
                "{\"command\":\"mvn test\"}"
        );

        assertThat(result.status()).isEqualTo(ToolPipelineResult.Status.APPROVAL_REQUIRED);
        assertThat(result.toolName()).isEqualTo("commandExecuteTool");
        assertThat(result.toolVersion()).isEqualTo("1.0.0");
        assertThat(result.argumentsHash()).matches("[0-9a-f]{64}");
        assertThat(result.toolResult()).isNull();
    }
}
