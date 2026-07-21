package com.hkdzagent.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class WorkspaceFileTool implements AgentTool<FileRequest, ToolResult> {

    private static final ToolMetadata METADATA = new ToolMetadata(
            "fileOperationTool",
            "1.0.0",
            "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"filePath\",\"content\"],\"additionalProperties\":false}",
            ToolRiskLevel.HIGH,
            ToolApprovalPolicy.ALWAYS,
            Duration.ofSeconds(5),
            ToolRetryPolicy.none()
    );

    private final ToolPermissionService permissionService;

    public WorkspaceFileTool(ToolPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public Class<FileRequest> inputType() {
        return FileRequest.class;
    }

    @Override
    public ToolResult execute(FileRequest request) {
        Path target = permissionService.resolveWorkspacePath(request.filePath());
        if (target == null) {
            return ToolResult.rejected("file path is not allowed");
        }

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(target, request.content());
            return ToolResult.success("file written successfully: " + target);
        } catch (Exception e) {
            return ToolResult.failure("file write failed: " + e.getMessage());
        }
    }
}
