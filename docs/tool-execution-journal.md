# Tool Execution Journal

The tool-execution journal is the durable boundary immediately before an `AgentTool` invocation. Both ordinary tool calls and approval-resumed calls pass through the same `ToolExecutionPipeline`.

## Identity and binding

The primary identity is `(run_id, tool_call_id)`. The first reservation also binds:

- tool name
- semantic tool version
- normalized argument SHA-256 hash
- a random execution token

Reusing the same tool-call ID with a different binding is rejected before tool execution. The execution token fences the completion update so a stale or competing caller cannot overwrite the result.

## State transitions

```text
missing --atomic reserve--> STARTED --fenced completion--> COMPLETED
                              |
                              +-- process loss --> STARTED remains uncertain
```

`COMPLETED` means a terminal tool result was durably recorded. The result can be `SUCCESS`, `REJECTED`, or `FAILED`; all three are replayed without invoking the tool again.

A surviving `STARTED` row means the process may have stopped before invocation, during invocation, or after an external side effect but before result persistence. The system therefore returns `EXECUTION_UNCERTAIN` and does not automatically retry.

## Run recovery decisions

| Evidence | Automatic action | Reason |
|---|---|---|
| No tool event or journal entry | Restart within attempt budget | No durable evidence of a tool side effect |
| Journal has `STARTED` | Fail and emit `RUN_RECOVERY_BLOCKED` | External result is uncertain |
| Journal has `COMPLETED` | Fail and emit `RUN_RECOVERY_BLOCKED` | Tool result exists, but no complete model continuation checkpoint exists |
| `TOOL_STARTED` event without journal | Fail and emit `RUN_RECOVERY_BLOCKED` | Legacy or inconsistent execution evidence |
| Recovery attempts exhausted | Fail and emit `RUN_RECOVERY_BLOCKED` | Bounded recovery policy |

## Operator investigation

Inspect a failed Run and its journal entries together:

```sql
SELECT id, status, error_message, updated_at
FROM agent_runs
WHERE id = :run_id;

SELECT tool_call_id, tool_name, tool_version, arguments_hash,
       status, result_status, started_at, completed_at
FROM tool_execution_journal
WHERE run_id = :run_id
ORDER BY started_at;
```

For `STARTED`, verify the target system using its own request logs or business identifiers before taking manual action. Do not update the journal to `COMPLETED` based only on assumption, and do not delete the row to force a retry. The current project intentionally has no automatic or administrator retry endpoint for uncertain tool effects.

## Exactly-once boundary

The journal guarantees one local reservation for a stable `(runId, toolCallId)` and prevents a second local submission after a durable result or uncertain start. It cannot atomically commit both PostgreSQL state and an arbitrary external side effect. Strict external exactly-once behavior requires the target API to accept and persist an idempotency key, or an equivalent transactional protocol.
