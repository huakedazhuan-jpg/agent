# Resume Notes

## One-line project description

Built a production-designed Spring Boot AI Agent system with durable PostgreSQL execution state, real SSE token streaming, restart-recoverable tool approval, JWT/RBAC, and Feishu integration.

## Resume bullets

- Designed a durable Agent Runtime state machine (`CREATED`, `RUNNING`, `WAITING_APPROVAL`, terminal states) backed by PostgreSQL, optimistic locking, Worker leases, provider checkpoints, and an ordered event log for SSE replay.
- Implemented OpenAI-compatible streaming tool calling by incrementally parsing text and function-call deltas, preserving tool-call IDs and arguments across chunks instead of splitting a completed answer.
- Built a human-in-the-loop safety workflow that pauses file and command tools before execution, persists resume context transactionally, prevents duplicate approval execution, and reconciles approved/rejected/expired decisions after restart.
- Added JWT authentication, `USER`/`ADMIN` RBAC, owner-scoped Runtime/trace/memory/approval access, PostgreSQL-backed users, BCrypt password storage, and production fail-fast configuration validation.
- Hardened Feishu processing with a PostgreSQL inbox and notification outbox, event/business-key deduplication, processing leases, retries, stale-work recovery, dead letters, ADMIN retry, audit records, and low-cardinality metrics.
- Maintained an automated Java test suite covering state transitions, JDBC repositories, transaction rollback, concurrency controls, streaming protocol parsing, approval recovery, API authorization, configuration safety, PostgreSQL 17 migrations, and database-process restart recovery.

## Interview walkthrough

Explain the Runtime in this order:

1. An API request creates an owned Run and its first durable event.
2. A Worker conditionally claims the Run and renews its lease while checkpointing model rounds.
3. Provider SSE chunks become ordered Runtime events and client SSE IDs.
4. A sensitive tool call stores provider context and transitions the Run to `WAITING_APPROVAL` before tool execution.
5. Approval atomically wins the pending decision and conditionally resumes the matching checkpoint; duplicate decisions cannot execute the tool twice.
6. A recovery scheduler reconciles durable decisions if the process exits between approval and background execution.

## Claims to avoid

- Do not call the system production-deployed; the repository demonstrates production-oriented design and tests.
- Claim PostgreSQL restart recovery only for the tested persistence and approval workflow; arbitrary abandoned `RUNNING` runs are not automatically resumed.
- Do not claim encrypted checkpoints, complete cancellation, token-event batching, pgvector hybrid retrieval, full metrics/SLOs, or proven load capacity.
