# XingClaw Agent

XingClaw Agent is a Spring Boot AI agent system focused on durable execution, tool calling, human approval, multi-user isolation, and external-channel integration.

The repository contains a production-designed Agent Runtime with PostgreSQL persistence, real provider token streaming, restart-recoverable tool approval, owner-scoped cooperative cancellation, fenced recovery of abandoned runs, a durable tool-execution journal, JWT/RBAC, owner-scoped resources, and durable Feishu notifications. Real PostgreSQL 17 migration, cancellation/approval concurrency, and database-process restart gates are implemented. It is suitable as a resume project, but should not be described as production-deployed: external exactly-once effects, post-tool model continuation checkpoints, checkpoint encryption, load testing, complete observability, and deployment automation remain incomplete.

## Current Status

- Backend tests pass with Maven Wrapper.
- Local static console is available at `/`.
- Durable Agent runs, ordered events, traces, tool approvals, chat memory, RAG, Feishu webhook processing, notification outbox delivery, and tool-safety controls exist.
- Local secrets are loaded from `.env`, which is intentionally ignored by Git.
- `.env.example` documents required local configuration keys.
- The project has been initialized as a Git repository for staged production-hardening work.

## Tech Stack

- Java 17+
- Spring Boot 3.5.16
- Spring AI OpenAI-compatible starter
- Maven Wrapper 3.9.14
- Spring Web MVC
- Reactor `Flux` for SSE responses
- Static HTML console
- Feishu OpenAPI webhook and durable inbox integration
- Spring Security OAuth2 Resource Server with HMAC-signed JWT access tokens

Spring AI is pinned to the stable 1.1.x line because this project currently stays on Spring Boot 3.x. Spring AI 2.x targets Spring Boot 4.x.

## Current Features

- Static browser console at `/`
- Blocking chat API at `/api/agent/chat`
- Console SSE endpoint at `/api/agent/chat/stream`
  - Reads real provider SSE deltas and persists ordered Runtime events with replayable SSE IDs.
- Durable Agent Runtime with state machine, optimistic versioning, fenced Worker leases, heartbeats, event replay, approval recovery, and classified recovery of expired `RUNNING` runs
- Human-in-the-loop approval that pauses sensitive tools before execution, conditionally resumes the bound invocation, and uses the execution journal to prevent duplicate local submission
- Owner-scoped, idempotent Run cancellation with durable events, cooperative model-loop stopping, approval cleanup, and an atomic run/journal gate before tool invocation
- Durable tool-execution journal keyed by Run and provider tool-call ID, with binding validation, result replay, completion fencing, and conservative blocking of uncertain side effects
- Agent trace with in-memory local adapter and optional PostgreSQL JDBC repository
- Tool confirmation queue with in-memory local adapter and optional PostgreSQL JDBC repository, expiry, and atomic decisions
- Chat memory with JSONL local adapter and optional PostgreSQL JDBC repository
- Tool registry with safety checks:
  - write a file inside the configured workspace
  - execute only explicitly allowed local commands
  - send HTTP GET requests only to configured domains
  - perform Tavily-backed web search
  - search the local knowledge base
- Local RAG prototype based on file-backed knowledge search
- Feishu webhook endpoint with URL verification, signature verification, durable event inbox option, async retry processing, token provider, and reply client
- Durable Feishu Inbox with event-to-Run binding and claim fencing, plus a notification outbox for approval-required, final-result, and run-failed messages, including retries, stale-claim recovery, dead letters, metrics, and ADMIN manual retry
- Baseline PostgreSQL, Redis, Docker Compose, and Flyway migration skeleton
- Optional JWT authentication with JDBC users, BCrypt password hashes, and `USER`/`ADMIN` RBAC
- Owner-scoped chat memory, Agent traces, and tool-approval lists for Web and Feishu actors

## Project Structure

```text
src/main/java/com/hkdzagent/agent
  ai/             Spring AI client and chat memory configuration
  console/        Durable tool confirmation records and decisions
  controller/     Web/API controllers
  im/             Feishu webhook and reply integration
  loop/           Bounded Agent planning/tool loop and pause result
  memory/         JSONL-backed chat memory
  model/          Request/response records
  rag/            Local knowledge base and search tool
  runtime/        Durable run state machine, event log, execution, approval recovery
  security/       JWT authentication, users, bootstrap account, and RBAC policy
  tool/           Tool registration and safety checks
  trace/          Sanitized execution trace and JDBC repository

src/main/resources
  application.yml
  application-dev.yml
  static/index.html

src/test/java
  Backend unit, integration, functional, configuration, tool-safety, RAG, trace, and Feishu tests

docs/
  Design notes and future architecture documents
  Infrastructure and quality gate documentation

verification/
  Isolated Spring AI compatibility checks
```

## Local Requirements

- JDK 17 or newer
- Maven Wrapper from this repository
- Network access to Maven repositories for first dependency resolution
- Valid OpenAI-compatible model credentials if calling the model
- Valid Feishu credentials if testing Feishu integration
- Valid Tavily credentials if testing Tavily search

## Local Configuration

Copy the example environment file:

```powershell
Copy-Item .env.example .env
```

Then fill in real local values.

Supported keys:

```properties
MOONSHOT_API_KEY=
MOONSHOT_BASE_URL=https://api.moonshot.ai
MOONSHOT_MODEL=kimi-k2.5
MOONSHOT_TEMPERATURE=1
MOONSHOT_MAX_TOKENS=16000

AGENT_KIMI_REQUEST_TIMEOUT=60s
AGENT_KIMI_MAX_TOOL_ROUNDS=5
AGENT_KIMI_HISTORY_LIMIT=20

FEISHU_APP_ID=
FEISHU_APP_SECRET=
FEISHU_VERIFICATION_TOKEN=
FEISHU_ENCRYPT_KEY=
FEISHU_ASYNC_CORE_SIZE=2
FEISHU_ASYNC_MAX_SIZE=4
FEISHU_ASYNC_QUEUE_CAPACITY=100
FEISHU_INBOX_REPOSITORY=memory
FEISHU_INBOX_MAX_ATTEMPTS=3
FEISHU_INBOX_RETRY_DELAY=30s
FEISHU_INBOX_PROCESSING_TIMEOUT=5m
FEISHU_INBOX_POLL_INTERVAL=30s
FEISHU_INBOX_POLL_BATCH_SIZE=20
FEISHU_OUTBOX_REPOSITORY=memory
FEISHU_OUTBOX_MAX_ATTEMPTS=5
FEISHU_OUTBOX_RETRY_DELAY=30s
FEISHU_OUTBOX_PROCESSING_TIMEOUT=5m
FEISHU_OUTBOX_POLL_INTERVAL=5s
FEISHU_OUTBOX_POLL_BATCH_SIZE=20

TAVILY_API_KEY=

POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=xingclaw_agent
POSTGRES_USER=xingclaw_agent
POSTGRES_PASSWORD=xingclaw-local-password
POSTGRES_MAX_POOL_SIZE=10
POSTGRES_MIN_IDLE=1
POSTGRES_CONNECTION_TIMEOUT_MS=2000

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=xingclaw-local-redis
REDIS_TIMEOUT=2s

SPRING_FLYWAY_ENABLED=false

AGENT_TRACE_REPOSITORY=memory
AGENT_AUDIT_REPOSITORY=memory
AGENT_RUNTIME_REPOSITORY=memory
AGENT_RUNTIME_MAX_STEPS=5
AGENT_RUNTIME_LEASE_DURATION=2m
AGENT_RUNTIME_EVENT_REPLAY_LIMIT=500
AGENT_TOOL_APPROVAL_REPOSITORY=memory
AGENT_TOOL_APPROVAL_TTL=15m
AGENT_TOOL_APPROVAL_REQUIRED_TOOLS=fileOperationTool,commandExecuteTool
AGENT_TOOL_APPROVAL_RECOVERY_INTERVAL=15s
AGENT_MEMORY_REPOSITORY=file
AGENT_MEMORY_FILE=data/chat-memory.jsonl
AGENT_RAG_INDEX_FILE=data/rag-index.json
AGENT_WORKSPACE_ROOT=./workspace

AGENT_SECURITY_ENABLED=false
AGENT_SECURITY_USER_REPOSITORY=memory
AGENT_SECURITY_JWT_ISSUER=xingclaw-agent
AGENT_SECURITY_JWT_SECRET=
AGENT_SECURITY_JWT_TTL=1h
AGENT_SECURITY_BOOTSTRAP_USERNAME=
AGENT_SECURITY_BOOTSTRAP_PASSWORD=
AGENT_SECURITY_BOOTSTRAP_ROLE=ADMIN
```

Do not commit `.env` or any real credentials.

Application configuration is bound through typed `@ConfigurationProperties` classes instead of scattered
`@Value` injection. This keeps runtime settings auditable and easier to validate as the project grows.

## Local Infrastructure

Start PostgreSQL and Redis:

```powershell
docker compose up -d postgres redis
```

Flyway is included but disabled by default so tests and local startup do not require a running database.
To apply migrations locally, start PostgreSQL first and then set:

```powershell
$env:SPRING_FLYWAY_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

To store Agent trace state in PostgreSQL instead of memory, also set:

```powershell
$env:AGENT_TRACE_REPOSITORY = "jdbc"
```

To store chat memory in PostgreSQL instead of the local JSONL file, set:

```powershell
$env:AGENT_MEMORY_REPOSITORY = "jdbc"
```

To store tool approvals in PostgreSQL with multi-instance-safe decisions, set:

```powershell
$env:AGENT_TOOL_APPROVAL_REPOSITORY = "jdbc"
```

To persist Agent runs, checkpoints, leases, and ordered events, set:

```powershell
$env:AGENT_RUNTIME_REPOSITORY = "jdbc"
```

Local defaults remain lightweight for development. The `prod` profile rejects non-JDBC Runtime, trace, memory, approval, administrator audit, Feishu inbox/outbox, and user repositories.

To persist and recover Feishu Webhook processing and approval/final/failure notification delivery, set both `FEISHU_INBOX_REPOSITORY=jdbc` and `FEISHU_OUTBOX_REPOSITORY=jdbc`. Production requires both settings; local development defaults to memory.

To exercise authentication locally, apply Flyway migrations and set:

```powershell
$env:AGENT_SECURITY_ENABLED = "true"
$env:AGENT_SECURITY_USER_REPOSITORY = "jdbc"
$env:AGENT_SECURITY_JWT_SECRET = "replace-with-at-least-32-random-bytes"
$env:AGENT_SECURITY_BOOTSTRAP_USERNAME = "admin"
$env:AGENT_SECURITY_BOOTSTRAP_PASSWORD = "replace-with-a-strong-password"
$env:AGENT_SECURITY_BOOTSTRAP_ROLE = "ADMIN"
```

The bootstrap account is inserted only when the username does not already exist; startup does not overwrite its password. See `docs/security.md` for endpoint policy and current limitations.

See `docs/infrastructure.md` for the infrastructure boundary and `docs/tool-execution-journal.md` for journal states, recovery decisions, and operator guidance.

## Build, Test, and Run

Check Maven Wrapper:

```powershell
.\mvnw.cmd -v
```

Run tests:

```powershell
.\mvnw.cmd test
```

Compile without tests:

```powershell
.\mvnw.cmd -DskipTests compile
```

Package without tests:

```powershell
.\mvnw.cmd -DskipTests package
```

Start the application:

```powershell
.\mvnw.cmd spring-boot:run
```

Open the local console:

```text
http://localhost:8080/
```

## Quality Gate

The repository includes a baseline GitHub Actions workflow at `.github/workflows/ci.yml`.

Current CI gate:

- `./mvnw -B --no-transfer-progress test`
- `./mvnw -B --no-transfer-progress -DskipTests package`
- PostgreSQL 17 migration, repository reconstruction, and real database-process restart recovery tests
- Concurrent expired-run recovery tests proving that only one PostgreSQL Worker can claim a stale lease
- Tool-journal concurrency, crash-window, and PostgreSQL process-restart tests
- PostgreSQL row-lock race tests for concurrent cancellation and approval decisions

See `docs/quality-gates.md` for the current quality gate and known CI/CD gaps.

## HTTP Endpoints

### Web Console

```text
GET /
```

### Blocking Agent Chat

```text
POST /api/agent/chat
Content-Type: application/json
```

Request:

```json
{
  "message": "hello",
  "sessionId": "user-123"
}
```

Response:

```json
{
  "answer": "..."
}
```

### Console SSE Chat

```text
POST /api/agent/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

Current event names:

- `created`
- `started`
- `token`
- `model-started`
- `model-completed`
- `tool-call-requested`
- `tool-started`
- `tool-completed`
- `approval-required`
- `cancelled`
- `final`
- `error`

Each event includes a durable per-run sequence ID. Provider text chunks are emitted as `payload.delta`, not by splitting a completed answer.

### Agent Runtime

```text
GET /api/agent/runs
GET /api/agent/runs/{runId}
GET /api/agent/runs/{runId}/events?after={sequence}
POST /api/agent/runs/{runId}/cancel
```

Runtime queries and event replay are owner-scoped. API views intentionally omit provider checkpoints because they may contain complete tool arguments.
Cancellation is owner-scoped and idempotent. It returns `404` for an unknown or another owner's Run, `409` for an already terminal non-cancelled Run, and closes a matching pending approval in the same transaction. Cancellation is cooperative: it prevents future model rounds and tool starts, but it cannot undo an external side effect that already crossed the tool-start boundary.

### Agent Trace

```text
GET /api/agent/traces
GET /api/agent/traces/{traceId}
```

Trace storage defaults to the in-memory adapter for local development and can be switched to PostgreSQL with `AGENT_TRACE_REPOSITORY=jdbc` after Flyway migrations are applied.

### Tool Confirmations

```text
GET  /api/agent/tool-confirmations
POST /api/agent/tool-confirmations/{confirmationId}/approve
POST /api/agent/tool-confirmations/{confirmationId}/reject
```

Approval records can be persisted in PostgreSQL, expire after a configurable TTL, and use atomic pending-state decisions. File and command tools pause before execution by default. Approval resumes the durable checkpoint once; rejection or expiry terminates the Run without executing the tool. A scheduled reconciler recovers decisions after process restarts.

### Authentication and RBAC

```text
POST /api/auth/login     public; returns a Bearer access token
GET  /api/auth/me        authenticated
/api/agent/**            authenticated
approve/reject endpoints ADMIN only
/api/agent/admin/**      ADMIN only
/actuator/metrics/**     ADMIN only
/actuator/health         public; details hidden
/test/**                 ADMIN only
POST /api/feishu/webhook public; protected by Feishu verification/signature checks
```

Authentication is disabled by default for zero-configuration local development. The `prod` profile fails at startup unless authentication is enabled, users use PostgreSQL, and the JWT secret contains at least 32 bytes.

### Feishu Webhook

```text
POST /api/feishu/webhook
```

Handles Feishu URL verification and `im.message.receive_v1` events. The optional JDBC inbox provides event-ID deduplication, atomic processing leases, scheduled retries, stale-work recovery, and terminal `DEAD` state.

Feishu approval-required, final-result, and run-failed notifications use a durable outbox. Operators with `ADMIN` can inspect aggregate state, list masked delivery records, and atomically requeue `DEAD` messages:

```text
GET  /api/agent/admin/feishu-outbox/summary
GET  /api/agent/admin/feishu-outbox/messages?status=DEAD&limit=20
POST /api/agent/admin/feishu-outbox/{id}/retry
```

Actuator exposes `health`, `info`, and `metrics`. Outbox gauges are available under `xingclaw.feishu.outbox.*`; metric labels never contain message text, run IDs, open IDs, or error text.

## Known Production Gaps

The following gaps are intentional tracking items for the production-grade upgrade:

- JWT authentication, `USER`/`ADMIN` RBAC, and owner checks exist for chat memory, traces, and approval lists; organization/tenant isolation is not implemented.
- Access tokens currently have no refresh, revocation, key rotation, or login rate limiting.
- PostgreSQL/Redis/Flyway infrastructure exists, and Agent Runtime/trace/chat memory/tool approvals/Feishu inbox/notification outbox/admin audit have JDBC repository switches. Redis is configured but is not yet used by application logic.
- Runtime and notification-outbox persistence are covered by H2 tests and real PostgreSQL 17 migration/recovery gates, including concurrent stale-run claiming and a database-process restart drill.
- Expired `RUNNING` runs are claimed by a recovery scheduler, and Feishu events are transactionally bound to their durable Run. Recovery after any tool activity remains deliberately manual because the project does not persist a complete post-tool model continuation checkpoint.
- Durable cooperative cancellation is implemented and race-tested through model, approval, and pre-tool boundaries. It does not forcibly interrupt arbitrary blocking provider calls or roll back external side effects that were already accepted.
- Provider checkpoints contain complete resume context and need production encryption plus retention cleanup.
- Token events currently write individually; batching is needed before high-throughput deployment.
- RAG is still local/file-backed, not pgvector hybrid retrieval.
- No production Docker Compose stack yet.
- CI runs unit/functional tests, packaging, PostgreSQL integration, and database restart recovery. Coverage thresholds, static analysis, container build, security scanning, and deployment gates are still missing.
- Micrometer outbox gauges exist, but Runtime/model/tool latency metrics, dashboards, alert thresholds, and SLOs are not implemented.
- Tool invocation validation and allow/approval/reject decisions exist, but production policy still needs environment- or tenant-specific administration and broader adversarial testing.
- The local tool journal prevents duplicate submission for the same durable tool-call identity and replays recorded terminal results. It cannot guarantee strict exactly-once effects for an external API that does not honor an idempotency key; a crash after the external effect but before journal completion is surfaced as `EXECUTION_UNCERTAIN` and blocked.

## Production Upgrade Roadmap

The project is being upgraded in staged phases:

1. Engineering baseline, Git, environment template, README cleanup
2. Dependency upgrade and configuration fail-fast checks
3. PostgreSQL, Redis, database migrations, and durable state migration
4. Authentication, JWT, RBAC, and owner-scoped object authorization baseline
5. Agent Runtime state machine and real SSE streaming (implemented)
6. Tool system, approval workflow, and human-in-the-loop safety (implemented baseline)
7. pgvector RAG with hybrid retrieval, citations, and evaluation
8. Feishu and market-data provider productionization
9. Observability, rate limiting, resilience, and SLOs
10. React management console
11. Docker Compose, CI, deployment docs, and resume materials

## Resume Positioning

Recommended current description:

> A production-designed Spring Boot AI Agent system with a PostgreSQL-backed execution state machine, real SSE token streaming, restart-recoverable human approval, owner-scoped cooperative cancellation, a fenced tool-execution journal, Worker lease recovery, JWT/RBAC, durable Feishu processing, and automated PostgreSQL concurrency/restart tests.

Do not yet claim production deployment, arbitrary external exactly-once effects, automatic continuation after completed tools, pgvector retrieval, complete observability, or proven high-concurrency capacity.
