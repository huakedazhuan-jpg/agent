# Infrastructure

This phase introduces baseline durable infrastructure. Agent trace, chat memory, tool approvals, and the Feishu event inbox can now use PostgreSQL through JDBC repositories.

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

Approval decisions use a conditional database update from `PENDING` to `APPROVED` or `REJECTED`. This prevents two application instances from deciding the same approval twice. Expired pending records transition to `EXPIRED`, and only sanitized argument previews are persisted.

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

The processing guarantee is at-least-once, not strict exactly-once. If an external Feishu reply succeeds and the process stops before the inbox row is marked `PROCESSED`, lease recovery can repeat the reply. Removing that final ambiguity requires an idempotency guarantee from the external send operation or a separate transactional outbox/send-receipt design.

The defaults remain `AGENT_TRACE_REPOSITORY=memory`, `AGENT_MEMORY_REPOSITORY=file`, `AGENT_TOOL_APPROVAL_REPOSITORY=memory`, and `FEISHU_INBOX_REPOSITORY=memory` so local tests and development startup do not require a running database.

## Current safety boundary

The local default passwords in `.env.example` and `docker-compose.yml` are only for development. Production must provide explicit database and Redis credentials through environment variables or a secret manager. Production also must enable Flyway and set `AGENT_TRACE_REPOSITORY=jdbc`, `AGENT_MEMORY_REPOSITORY=jdbc`, `AGENT_TOOL_APPROVAL_REPOSITORY=jdbc`, and `FEISHU_INBOX_REPOSITORY=jdbc`.
