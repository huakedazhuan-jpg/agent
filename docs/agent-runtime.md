# Agent Runtime

The Agent Runtime is the durable execution layer between API requests, model calls, tools, approvals, and SSE clients. It is separate from Agent Trace: Runtime state answers what should execute next, while Trace records a sanitized observability view of what happened.

## State machine

```text
CREATED -> RUNNING
CREATED -> CANCELLED
RUNNING -> WAITING_APPROVAL | COMPLETED | FAILED | CANCELLED
WAITING_APPROVAL -> RUNNING | FAILED | CANCELLED
```

Terminal states cannot transition again. `WAITING_APPROVAL` must reference a pending approval. Completion and failure cannot occur directly from `CREATED` because a worker must first claim and start the run.

## Durable aggregate

`agent_runs` stores:

- owner and external session identity
- internal conversation ID and Trace ID
- current status and tool step
- maximum tool-step bound
- optimistic concurrency version
- last durable event sequence
- provider-neutral JSON checkpoint
- pending approval reference
- final answer or sanitized error
- worker lease and timestamps

The checkpoint is intentionally opaque to the Runtime core. The Kimi adapter will serialize provider-specific messages and pending tool-call data without leaking that representation into the state machine API.

## Runtime events

`agent_run_events` is an ordered per-run event log. `(run_id, sequence)` is unique and provides stable SSE event IDs and replay ordering.

Event types cover run lifecycle, model lifecycle, token deltas, tool requests/results, approval waits, completion, failure, and cancellation. Token deltas may later be batched before persistence if per-token writes become too expensive; the ordering contract remains unchanged.

## Concurrency

The Runtime uses two different controls:

- `version` prevents stale state updates from overwriting a newer checkpoint.
- worker lease fields prevent multiple application instances from advancing the same runnable run concurrently.

Approval decisions retain their existing atomic pending-state update. Resuming a run must additionally use a conditional `WAITING_APPROVAL -> RUNNING` transition so duplicate approval callbacks cannot execute a tool twice.

## Current implementation boundary

V8, the Runtime domain model, memory/JDBC repositories, worker leasing, optimistic state updates, ordered event append/replay, and the Runtime executor are implemented. The streaming chat endpoint now executes the Agent Loop through Runtime, persists model/tool lifecycle events, emits provider token deltas with durable SSE IDs, and exposes owner-scoped replay from a requested sequence.

Sensitive tools are gated before execution. Runtime persists the provider checkpoint, enters `WAITING_APPROVAL`, and releases its worker lease. An approved decision conditionally resumes the matching run and executes the saved tool call once; rejection or expiry fails the run without tool execution. A recovery scheduler reconciles durable decisions after process restarts.

The non-streaming chat endpoint still uses the compatibility path. High-volume token-event batching and real PostgreSQL integration verification remain later hardening work.
