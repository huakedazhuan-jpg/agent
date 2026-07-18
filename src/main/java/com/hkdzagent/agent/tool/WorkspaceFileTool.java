package com.hkdzagent.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceFileTool {

    private final ToolPermissionService permissionService;

    public WorkspaceFileTool(ToolPermissionService permissionService) {
        this.permissionService = permissionService;
    }

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
