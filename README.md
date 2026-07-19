# XingClaw Agent

XingClaw Agent is a Spring Boot based AI agent project. It is currently an engineering prototype being upgraded into a production-grade resume project.

The current codebase can compile and pass tests, but it should not yet be described as production-ready. Important production capabilities such as persistent multi-user identity, real token-by-token Agent Runtime streaming, approval-gated tool execution, Docker deployment, and observability are still planned work.

## Current Status

- Backend tests pass with Maven Wrapper.
- Local static console is available at `/`.
- Chat, trace, tool-confirmation, RAG, Feishu webhook, and tool-safety prototypes exist.
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
- Feishu OpenAPI integration prototype

Spring AI is pinned to the stable 1.1.x line because this project currently stays on Spring Boot 3.x. Spring AI 2.x targets Spring Boot 4.x.

## Current Features

- Static browser console at `/`
- Blocking chat API at `/api/agent/chat`
- Console SSE endpoint at `/api/agent/chat/stream`
  - Current limitation: it emits lifecycle events and the final answer, but does not yet implement true token-by-token Agent Runtime streaming.
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
- Baseline PostgreSQL, Redis, Docker Compose, and Flyway migration skeleton

## Project Structure

```text
src/main/java/com/hkdzagent/agent
  ai/             Spring AI client and chat memory configuration
  console/        Tool confirmation prototype
  controller/     Web/API controllers
  im/             Feishu webhook and reply integration
  loop/           Agent loop prototype
  memory/         JSONL-backed chat memory
  model/          Request/response records
  rag/            Local knowledge base and search tool
  tool/           Tool registration and safety checks
  trace/          Agent trace prototype

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
AGENT_TOOL_APPROVAL_REPOSITORY=memory
AGENT_TOOL_APPROVAL_TTL=15m
AGENT_MEMORY_REPOSITORY=file
AGENT_MEMORY_FILE=data/chat-memory.jsonl
AGENT_RAG_INDEX_FILE=data/rag-index.json
AGENT_WORKSPACE_ROOT=./workspace
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

The defaults remain `AGENT_TRACE_REPOSITORY=memory`, `AGENT_MEMORY_REPOSITORY=file`, and `AGENT_TOOL_APPROVAL_REPOSITORY=memory` for fast local tests and development startup. The `prod` profile rejects these defaults and requires all three repositories to use `jdbc`.

To persist and recover Feishu Webhook processing, set `FEISHU_INBOX_REPOSITORY=jdbc`. Production also requires this setting; local development defaults to memory.

See `docs/infrastructure.md` for the current infrastructure boundary.

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

- `started`
- `token`
- `final`
- `error`

Current limitation: `token` currently contains the full final answer. Real token-by-token streaming is planned in the Agent Runtime phase.

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

Approval records can be persisted in PostgreSQL, expire after a configurable TTL, and use atomic pending-state decisions. Current limitation: tool execution is not yet paused and resumed by this approval state, so the end-to-end human-in-the-loop workflow is not complete.

### Feishu Webhook

```text
POST /api/feishu/webhook
```

Handles Feishu URL verification and `im.message.receive_v1` events. The optional JDBC inbox provides event-ID deduplication, atomic processing leases, scheduled retries, stale-work recovery, and terminal `DEAD` state.

## Known Production Gaps

The following gaps are intentional tracking items for the production-grade upgrade:

- No multi-user authentication or object-level authorization yet.
- PostgreSQL/Redis/Flyway infrastructure exists, and Agent trace/chat memory/tool approvals/Feishu inbox have JDBC repository switches.
- Agent streaming is not yet true token-by-token runtime streaming.
- Tool approval persistence is durable, but approval is not yet connected to pause/resume tool execution.
- RAG is still local/file-backed, not pgvector hybrid retrieval.
- No production Docker Compose stack yet.
- Only a baseline CI quality gate exists; coverage, static analysis, container build, and integration-test gates are still missing.
- No Prometheus/Grafana observability yet.
- Tooling is safer than the initial prototype, but production tool policy still needs a full allow/approval/deny pipeline.

## Production Upgrade Roadmap

The project is being upgraded in staged phases:

1. Engineering baseline, Git, environment template, README cleanup
2. Dependency upgrade and configuration fail-fast checks
3. PostgreSQL, Redis, database migrations, and durable state migration
4. Authentication, JWT, RBAC, and object-level authorization
5. Agent Runtime state machine and real SSE streaming
6. Tool system, approval workflow, and human-in-the-loop safety
7. pgvector RAG with hybrid retrieval, citations, and evaluation
8. Feishu and market-data provider productionization
9. Observability, rate limiting, resilience, and SLOs
10. React management console
11. Docker Compose, CI, deployment docs, and resume materials

## Resume Positioning

Before the production upgrade is complete, describe this project as:

> A Spring Boot AI agent engineering prototype with tool calling, local RAG, Feishu integration, trace, and safety checks.

After the planned production-hardening phases are complete, it can be described more strongly as:

> A production-designed public-information research Agent system with multi-user access control, streaming Agent Runtime, human-in-the-loop tool approval, pgvector RAG, Feishu integration, observability, Docker deployment, and evaluation tests.
