# Infrastructure

The durable infrastructure supports Agent Runtime state, traces, chat memory, tool approvals, users, administrator audit, and the Feishu event inbox/notification outbox through PostgreSQL JDBC repositories.

## Services

Local development infrastructure is defined in `docker-compose.yml`:

- PostgreSQL 17 for durable application state
- Redis 8 for future cache, rate-limit, and short-lived coordination work; application logic does not use Redis yet

Start local infrastructure:

```powershell
docker compose up -d postgres redis
```

Spring Boot 3.5.16 manages Flyway 11.7.2, and this repository validates that combination against PostgreSQL 17. Upgrade Flyway and PostgreSQL together after compatibility testing. The volume is named `postgres17-data` so an older PostgreSQL 18 volume cannot be attached accidentally.

Stop local infrastructure without removing data:

```powershell
docker compose down
```

Remove local volumes only when durable local data is no longer needed:

```powershell
docker compose down -v
```

## Database migrations

Flyway migrations live under `src/main/resources/db/migration/postgresql`. Flyway is disabled by default so local startup and unit tests do not require PostgreSQL:

```properties
SPRING_FLYWAY_ENABLED=false
```

To apply migrations during local startup:

```powershell
$env:SPRING_FLYWAY_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

Migrations V1-V15 create durable state for:

- conversations and messages
- Agent trace aggregates and events
- tool approvals
- application users, roles, and user-role assignments
- owner keys and owner-scoped indexes
- Agent runs, provider checkpoints, Worker leases, and ordered Runtime events
- Feishu event inbox
- Feishu approval-required, final-result, and run-failed notification outbox
- administrator audit events
- tool-execution reservations, fenced completion results, and recovery evidence

## JDBC repository switches

Local defaults stay lightweight. Production enables Flyway and selects JDBC for every durable repository:

```properties
AGENT_RUNTIME_REPOSITORY=jdbc
AGENT_TRACE_REPOSITORY=jdbc
AGENT_MEMORY_REPOSITORY=jdbc
AGENT_TOOL_APPROVAL_REPOSITORY=jdbc
AGENT_AUDIT_REPOSITORY=jdbc
AGENT_SECURITY_USER_REPOSITORY=jdbc
FEISHU_INBOX_REPOSITORY=jdbc
FEISHU_OUTBOX_REPOSITORY=jdbc
```

Runtime and approval timing are configurable:

```properties
AGENT_RUNTIME_LEASE_DURATION=2m
AGENT_RUNTIME_HEARTBEAT_INTERVAL=30s
AGENT_RUNTIME_RECOVERY_INTERVAL=15s
AGENT_RUNTIME_RECOVERY_BATCH_SIZE=10
AGENT_RUNTIME_MAX_RECOVERY_ATTEMPTS=3
AGENT_RUNTIME_EVENT_REPLAY_LIMIT=500
AGENT_TOOL_APPROVAL_TTL=15m
```

Approval decisions use conditional database updates from `PENDING` to a terminal decision. Expired pending records become `EXPIRED`. Runtime uses optimistic versions to reject stale updates and Worker leases to prevent simultaneous advancement by different instances. Per-run event sequences provide stable SSE IDs.

Approval rows expose sanitized argument previews. The linked Runtime checkpoint contains complete provider context needed to resume an exact tool invocation, so checkpoint encryption and retention cleanup remain production requirements.

## PostgreSQL verification

Run repository reconstruction and persistence verification with:

```powershell
$env:RUN_POSTGRES_INTEGRATION_TESTS = "true"
.\mvnw.cmd -Dtest=RealPostgresAgentRuntimeIntegrationTest test
```

The real database-process restart drill is documented in [quality-gates.md](quality-gates.md). It compares `pg_postmaster_start_time()` before and after restart, so reconstructing Java repository objects cannot be mistaken for database restart recovery. The drill uses an isolated schema and removes it after verification.

## Feishu inbox

Configure durable webhook processing with:

```properties
FEISHU_INBOX_REPOSITORY=jdbc
FEISHU_INBOX_MAX_ATTEMPTS=3
FEISHU_INBOX_RETRY_DELAY=30s
FEISHU_INBOX_PROCESSING_TIMEOUT=5m
FEISHU_INBOX_POLL_INTERVAL=30s
FEISHU_INBOX_POLL_BATCH_SIZE=20
```

The inbox stores each event before asynchronous processing, deduplicates by event ID, atomically claims work, retries transient failures, recovers stale processing leases, and moves exhausted events to `DEAD`.

Each Inbox event can be bound to one Agent Run through `feishu_event_inbox.run_id`. Run creation, trace creation, and the conditional event binding share one transaction. The Inbox retry count is also used as a claim token, so a stale processor cannot bind a Run or overwrite the outcome of a newer claim. If a process exits after binding, recovery reuses the bound Run instead of creating another one; abandoned `RUNNING` work is delegated to the Runtime recovery policy.

## Feishu notification outbox

Approval-required, final-result, and run-failed notifications are enqueued in the same database transaction as their Runtime transition. The outbox:

- deduplicates by a business key while allowing multiple notification types for one run
- claims and sends independently from Runtime execution
- retries transient failures and recovers stale processing leases
- moves exhausted deliveries to `DEAD`
- allows ADMIN users to inspect masked records and requeue dead messages
- writes successful manual retries to the administrator audit repository
- exposes low-cardinality Micrometer gauges under `xingclaw.feishu.outbox.*`

Delivery is at-least-once, not strict exactly-once. If Feishu accepts a message and the process stops before the row is marked `SENT`, lease recovery can send the reply again. Removing that ambiguity requires an idempotency key honored by the external API.

## Runtime recovery boundary

Approval decisions are restart-recoverable. A scheduled Runtime recovery worker also atomically claims expired `RUNNING` rows with `FOR UPDATE SKIP LOCKED`, advances the lease epoch, and classifies durable event history before dispatch.

Runs are automatically restarted from their original request only when neither Runtime events nor the tool journal show tool activity and the configured recovery-attempt limit has not been reached. Recovery distinguishes an unfinished journal entry, a completed tool without a resumable model checkpoint, and a legacy `TOOL_STARTED` event without journal evidence. Each unsafe case is failed with a durable `RUN_RECOVERY_BLOCKED` event instead of replaying the request.

`tool_execution_journal` reserves `(run_id, tool_call_id)` before invocation and binds it to the tool name, version, and normalized argument hash. A random execution token fences result completion. Terminal results are replayable after process restart; a surviving `STARTED` row is never automatically re-executed. This provides local duplicate-submission prevention, not strict external exactly-once semantics. See [tool-execution-journal.md](tool-execution-journal.md).

## Current safety boundary

Values in `.env.example` and `docker-compose.yml` are development-only. Production must provide explicit database and Redis credentials through environment variables or a secret manager. The `prod` profile requires Flyway, JDBC durable repositories, authentication, and a JWT secret of at least 32 bytes.

Redis is present in the infrastructure baseline but is not used by application logic. It should not be claimed as an implemented cache, distributed lock, or rate limiter.
