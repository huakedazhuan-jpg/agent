package com.hkdzagent.agent.tool;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class CommandExecuteTool {

    private final ToolPermissionService permissionService;

    public CommandExecuteTool(ToolPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public ToolResult execute(CommandRequest request) throws Exception {
        String command = permissionService.normalizeCommand(request.command());
        if (!permissionService.isCommandAllowed(command)) {
            return ToolResult.rejected("command is not allowed");
        }

        ProcessBuilder processBuilder = isWindows()
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return ToolResult.rejected("command timed out");
        }

        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        return ToolResult.success("command executed successfully:\n" + output);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
