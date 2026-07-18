package com.hkdzagent.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class ToolExecutionSupport {

    private final Logger logger;

    public ToolExecutionSupport() {
        this(LoggerFactory.getLogger(ToolExecutionSupport.class));
    }

    ToolExecutionSupport(Logger logger) {
        this.logger = logger;
    }

    public String execute(String toolName, Callable<ToolResult> operation, String failurePrefix) {
        logger.debug("Executing tool {}", toolName);
        try {
            ToolResult result = operation.call();
            if (result == null) {
                return failureMessage(failurePrefix, "tool returned no result");
            }
            logResult(toolName, result);
            return result.message();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Tool {} was interrupted", toolName, e);
            return failureMessage(failurePrefix, e.getMessage());
        } catch (Exception e) {
            logger.warn("Tool {} failed unexpectedly", toolName, e);
            return failureMessage(failurePrefix, e.getMessage());
        }
    }

    private void logResult(String toolName, ToolResult result) {
        if (result.status() == ToolResult.Status.REJECTED) {
            logger.debug("Tool {} was rejected", toolName);
            return;
        }
        if (result.status() == ToolResult.Status.FAILED) {
            logger.debug("Tool {} failed", toolName);
            return;
        }
        logger.debug("Tool {} completed successfully", toolName);
    }

    private String failureMessage(String failurePrefix, String detail) {
        String prefix = failurePrefix == null || failurePrefix.isBlank() ? "tool execution failed" : failurePrefix;
        if (detail == null || detail.isBlank()) {
            return prefix;
        }
        return prefix + ": " + detail;
    }
}
