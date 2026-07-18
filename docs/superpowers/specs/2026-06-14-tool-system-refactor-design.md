# Tool System Refactor Design

## Goal

Refactor the current tool system so tools are maintainable without breaking the existing external contract. Existing tool bean names, model tool names, and string results remain compatible while implementation moves out of `ToolRegistryConfig`.

## Current State

`ToolRegistryConfig` currently owns too many responsibilities:

- Spring bean registration.
- Request DTO definitions.
- File, command, HTTP, and search tool business logic.
- Permission checks.
- Error handling and user-facing result messages.
- Low-level helpers such as path resolution, command normalization, HTTP allow-list checks, timeouts, and response limiting.

`KimiToolCallingClient` currently depends on bean names and request types from `ToolRegistryConfig`, and its tests assert the tool names sent to the model:

- `fileOperationTool`
- `commandExecuteTool`
- `httpRequestTool`

The refactor must preserve these names and compatible string output so existing tool calls continue to work.

## Recommended Approach

Use a compatibility adapter around a new internal tool architecture.

`ToolRegistryConfig` remains the Spring configuration entry point, but it only creates beans. Each tool becomes an independent class with its own request record and execution logic. Shared concerns move into services:

- `ToolResult`: common result value for success, rejection, and failure.
- `ToolExecutionSupport`: common execution wrapper for logging, exception handling, interruption handling, and final string formatting.
- `ToolPermissionService`: common permission checks for file paths, command allow-list validation, and HTTP URL/domain validation.
- `WorkspaceFileTool`: writes files inside the configured workspace.
- `CommandExecuteTool`: executes only explicitly allowed commands.
- `HttpRequestTool`: sends HTTP GET requests only to allowed domains with configured timeouts and response-size limits.
- `WebSearchTool`: performs Tavily search with the configured API key.

## Compatibility Contract

The existing Spring beans stay available with the same bean names:

- `fileOperationTool`
- `commandExecuteTool`
- `httpRequestTool`
- `webSearchTool`

The beans continue to expose `Function<..., String>` so existing Spring AI and `KimiToolCallingClient` wiring remains compatible.

Request DTOs move to dedicated files. `ToolRegistryConfig` keeps nested request records as compatibility aliases during this refactor so existing tests or callers that reference `ToolRegistryConfig.FileRequest`, `ToolRegistryConfig.CommandRequest`, `ToolRegistryConfig.WebRequest`, and `ToolRegistryConfig.SearchRequest` do not break in this step.

Result strings remain compatible with existing behavior:

- Permission denials contain `rejected`.
- File write success contains `file written successfully`.
- Command success contains `command executed successfully`.
- Command timeout contains `command timed out`.
- HTTP timeout contains `timeout` or `timed out`.
- Tool failures contain the relevant operation context, such as `file write failed`, `command execution failed`, `network request failed`, or `search failed`.

## Error Handling And Logging

Tool code returns `ToolResult` rather than constructing ad hoc strings. `ToolExecutionSupport` converts `ToolResult` to the final string and catches unexpected exceptions.

All tools use the same execution wrapper:

- Log execution start at debug level with the tool name.
- Log success at debug level.
- Log permission rejection at warn level.
- Log unexpected failures at warn level without throwing to the model caller.
- Preserve interrupted status when an `InterruptedException` occurs.

The wrapper avoids logging sensitive request bodies or API keys.

## Permission Model

`ToolPermissionService` centralizes permission logic backed by `ToolSecurityProperties`:

- File writes are allowed only when the normalized absolute target path is inside the configured workspace root.
- Commands are allowed only when the normalized command exactly matches `agent.tools.security.allowed-commands`.
- Commands containing shell control operators remain rejected.
- HTTP requests require `http` or `https`, a non-empty host, and an exact host match in `agent.tools.security.http.allowed-domains`.
- HTTP connect timeout, read timeout, and maximum response bytes keep their existing defaults when missing or invalid.

## Testing Strategy

Use TDD for implementation.

First add tests that fail against the current design:

- A structure test proves each tool has its own class.
- A registry test proves `ToolRegistryConfig` only exposes compatible bean registration and no longer owns business helper methods.
- A result test proves `ToolExecutionSupport` converts success, rejection, failure, and interruption into consistent strings.
- Permission tests prove centralized permission behavior.

Then make minimal implementation changes until the new tests and existing tests pass.

Existing tests remain valuable compatibility coverage:

- `ToolRegistryConfigSecurityTest` verifies file, command, and HTTP security behavior.
- `KimiToolCallingClientTest` verifies tool names and tool-call flow.
- `AgentApplicationTests` verifies Spring context wiring.

## Out Of Scope

This refactor does not change model prompt behavior, tool names, endpoint behavior, Feishu integration, or the Tavily search exposure in `KimiToolCallingClient`.

It also does not introduce a dynamic runtime registry, schema generator, or plugin system. Those can be added later after the current tool boundaries are clean.
