# Infrastructure

The durable infrastructure supports Agent Runtime state, trace, chat memory, tool approvals, users, and the Feishu event inbox through PostgreSQL JDBC repositories.

## Services

Local development infrastructure is defined in `docker-compose.yml`:

- PostgreSQL 18 for durable application state
- Redis 8 for future cache, rate-limit, and short-lived runtime coordination

Start local infrastructure:

```powershell
docker compose up -d postgres redis
```

Stop local infrastructure:

```powershell
docker compose down
```

Remove local volumes:

```powershell
docker compose down -v
```

## Database migrations

Flyway migration files live under:

```text
src/main/resources/db/migration/postgresql
```

Flyway is present but disabled by default:

```properties
SPRING_FLYWAY_ENABLED=false
```

To apply migrations during local startup, first start PostgreSQL and then enable Flyway:

```powershell
$env:SPRING_FLYWAY_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

The migrations create durable-state tables for:

- conversations
- messages
- agent trace aggregate records and events
- tool approvals
- Feishu event inbox
- application users, roles, and user-role assignments
- owner keys and owner-scoped indexes for conversations, traces, and tool approvals
- Agent runs, provider checkpoints, Worker leases, and ordered Runtime events

Agent trace can now use PostgreSQL:

```properties
AGENT_TRACE_REPOSITORY=jdbc
```

Chat memory can now use PostgreSQL:

```properties
AGENT_MEMORY_REPOSITORY=jdbc
```

Tool approvals can now use PostgreSQL with a configurable expiry:

```properties
AGENT_TOOL_APPROVAL_REPOSITORY=jdbc
AGENT_TOOL_APPROVAL_TTL=15m
```

Agent Runtime can persist resumable execution state and events:

```properties
AGENT_RUNTIME_REPOSITORY=jdbc
AGENT_RUNTIME_LEASE_DURATION=2m
AGENT_RUNTIME_EVENT_REPLAY_LIMIT=500
```

Approval decisions use a conditional database update from `PENDING` to `APPROVED` or `REJECTED`. This prevents two application instances from deciding the same approval twice. Expired pending records transition to `EXPIRED`. Approval rows expose only sanitized argument previews; the linked Runtime checkpoint contains full provider context needed for recovery and therefore requires production encryption and retention controls.

Runtime uses optimistic versions to reject stale checkpoint updates and Worker leases to prevent simultaneous execution. Per-run event sequences provide stable SSE IDs. A recovery scheduler reconciles durable approval decisions after application restarts.

Feishu Webhook events can use a durable inbox:

```properties
FEISHU_INBOX_REPOSITORY=jdbc
FEISHU_INBOX_MAX_ATTEMPTS=3
FEISHU_INBOX_RETRY_DELAY=30s
FEISHU_INBOX_PROCESSING_TIMEOUT=5m
FEISHU_INBOX_POLL_INTERVAL=30s
FEISHU_INBOX_POLL_BATCH_SIZE=20
```

The inbox stores each event before asynchronous processing, deduplicates by event ID, atomically claims work, retries transient failures, recovers stale processing leases, and moves exhausted events to `DEAD` for inspection.

Migration V7 scopes runtime data by Actor. Web users use `user:<JWT subject>`, Feishu conversations use `feishu:<openId>`, and legacy records are marked `legacy:unowned`. Conversation uniqueness is owner-scoped so identical external session IDs from different users do not collide.

The processing guarantee is at-least-once, not strict exactly-once. If an external Feishu reply succeeds and the process stops before the inbox row is marked `PROCESSED`, lease recovery can repeat the reply. Removing that final ambiguity requires an idempotency guarantee from the external send operation or a separate transactional outbox/send-receipt design.

The defaults remain `AGENT_RUNTIME_REPOSITORY=memory`, `AGENT_TRACE_REPOSITORY=memory`, `AGENT_MEMORY_REPOSITORY=file`, `AGENT_TOOL_APPROVAL_REPOSITORY=memory`, and `FEISHU_INBOX_REPOSITORY=memory` so local tests and development startup do not require a running database.

## Current safety boundary

The local default passwords in `.env.example` and `docker-compose.yml` are only for development. Production must provide explicit database and Redis credentials through environment variables or a secret manager. Production also must enable Flyway; use JDBC for Runtime, traces, memory, approvals, the Feishu inbox, and users; enable authentication; and supply a JWT secret of at least 32 bytes.
