# Resume Notes

## One-line project description

Built a production-designed Spring Boot AI Agent system with durable PostgreSQL execution state, real SSE token streaming, restart-recoverable tool approval, owner-scoped cooperative cancellation, a fenced tool-execution journal, JWT/RBAC, and Feishu integration.

## Resume bullets

- Designed a durable Agent Runtime state machine (`CREATED`, `RUNNING`, `WAITING_APPROVAL`, terminal states) backed by PostgreSQL, optimistic locking, Worker leases, provider checkpoints, and an ordered event log for SSE replay.
- Implemented OpenAI-compatible streaming tool calling by incrementally parsing text and function-call deltas, preserving tool-call IDs and arguments across chunks instead of splitting a completed answer.
- Built a human-in-the-loop safety workflow that pauses file and command tools before execution, persists resume context transactionally, prevents duplicate approval execution, and reconciles approved/rejected/expired decisions after restart.
- Implemented owner-scoped, idempotent Run cancellation with durable state/events, transactional pending-approval cleanup, cooperative model stopping, and an atomic Run-lease/tool-journal start gate; verified approval/cancellation lock races on PostgreSQL 17.
- Added JWT authentication, `USER`/`ADMIN` RBAC, owner-scoped Runtime/trace/memory/approval access, PostgreSQL-backed users, BCrypt password storage, and production fail-fast configuration validation.
- Hardened Feishu processing with a PostgreSQL inbox and notification outbox, event-to-Run binding, fenced processing claims, event/business-key deduplication, retries, stale-work recovery, dead letters, ADMIN retry, audit records, and low-cardinality metrics.
- Maintained an automated Java test suite covering state transitions, JDBC repositories, transaction rollback, concurrency controls, streaming protocol parsing, approval recovery, API authorization, configuration safety, PostgreSQL 17 migrations, and database-process restart recovery.
- Implemented lease-epoch fencing, heartbeat renewal, `SKIP LOCKED` stale-run claiming, bounded model-stage recovery, and conservative blocking when tool side effects are indeterminate.
- Added a PostgreSQL tool-execution journal keyed by Run/tool-call ID with atomic reservation, argument/version binding, fenced completion, terminal-result replay, and journal-aware crash recovery; verified persistence across a real PostgreSQL process restart.

## Interview walkthrough

Explain the Runtime in this order:

1. An API request creates an owned Run and its first durable event.
2. A Worker conditionally claims the Run and renews its lease while checkpointing model rounds.
3. Provider SSE chunks become ordered Runtime events and client SSE IDs.
4. A sensitive tool call stores provider context and transitions the Run to `WAITING_APPROVAL` before tool execution.
5. Approval atomically wins the pending decision and conditionally resumes the matching checkpoint; duplicate decisions cannot execute the tool twice.
6. A recovery scheduler reconciles durable decisions if the process exits between approval and background execution.
7. Every tool reserves a durable journal row before invocation; duplicate completed calls replay the stored result, while unfinished calls are blocked for investigation.
8. Cancellation is a durable competing transition: it stops future model/tool work, closes a pending approval transactionally, and shares a Run-first lock order with approval decisions.

## Claims to avoid

- Do not call the system production-deployed; the repository demonstrates production-oriented design and tests.
- Describe abandoned-run recovery as classified and bounded: model-only runs can restart, while unfinished tools, completed tools without a model continuation checkpoint, and legacy tool events without journal evidence are failed for manual investigation.
- Do not call the journal arbitrary external exactly-once. It prevents duplicate local submission for a stable tool-call identity; external exactly-once requires an idempotency contract provided by the target system.
- Do not describe cancellation as forcibly interrupting arbitrary blocking calls or reversing external side effects that already started.
- Do not claim encrypted checkpoints, token-event batching, pgvector hybrid retrieval, full metrics/SLOs, or proven load capacity.
