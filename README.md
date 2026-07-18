# XingClaw Agent

XingClaw Agent is a Spring Boot based AI agent project. It is currently an engineering prototype being upgraded into a production-grade resume project.

The current codebase can compile and pass tests, but it should not yet be described as production-ready. Important production capabilities such as persistent multi-user identity, PostgreSQL/Redis state, real token-by-token Agent Runtime streaming, durable tool approval, CI, Docker deployment, and observability are still planned work.

## Current Status

- Backend tests pass with Maven Wrapper.
- Local static console is available at `/`.
- Chat, trace, tool-confirmation, RAG, Feishu webhook, and tool-safety prototypes exist.
- Local secrets are loaded from `.env`, which is intentionally ignored by Git.
- `.env.example` documents required local configuration keys.
- The project has been initialized as a Git repository for staged production-hardening work.

## Tech Stack

- Java 17+
- Spring Boot 3.3.0
- Spring AI OpenAI-compatible starter
- Maven Wrapper 3.9.14
- Spring Web MVC
- Reactor `Flux` for SSE responses
- Static HTML console
- Feishu OpenAPI integration prototype

Current Spring AI usage still depends on a milestone version. Upgrading to a stable Spring AI release is tracked as Phase 1 work.

## Current Features

- Static browser console at `/`
- Blocking chat API at `/api/agent/chat`
- Console SSE endpoint at `/api/agent/chat/stream`
  - Current limitation: it emits lifecycle events and the final answer, but does not yet implement true token-by-token Agent Runtime streaming.
- In-memory Agent trace prototype
- Tool confirmation queue prototype
- JSONL-backed persistent chat memory prototype
- Tool registry with safety checks:
  - write a file inside the configured workspace
  - execute only explicitly allowed local commands
  - send HTTP GET requests only to configured domains
  - perform Tavily-backed web search
  - search the local knowledge base
- Local RAG prototype based on file-backed knowledge search
- Feishu webhook endpoint with URL verification, signature verification hook, event deduplication, async processing, token provider, and reply client prototypes

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

FEISHU_APP_ID=
FEISHU_APP_SECRET=

TAVILY_API_KEY=

AGENT_WORKSPACE_ROOT=./workspace
```

Do not commit `.env` or any real credentials.

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

Start the application:

```powershell
.\mvnw.cmd spring-boot:run
```

Open the local console:

```text
http://localhost:8080/
```

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

### Tool Confirmations

```text
GET  /api/agent/tool-confirmations
POST /api/agent/tool-confirmations/{confirmationId}/approve
POST /api/agent/tool-confirmations/{confirmationId}/reject
```

Current limitation: the confirmation service exists as a console prototype and is not yet a durable production approval workflow.

### Feishu Webhook

```text
POST /api/feishu/webhook
```

Handles Feishu URL verification and `im.message.receive_v1` events.

## Known Production Gaps

The following gaps are intentional tracking items for the production-grade upgrade:

- No multi-user authentication or object-level authorization yet.
- No PostgreSQL/Redis-backed durable runtime state yet.
- Agent streaming is not yet true token-by-token runtime streaming.
- Agent trace and tool confirmation are not yet durable production workflows.
- RAG is still local/file-backed, not pgvector hybrid retrieval.
- Feishu event handling is not yet backed by a persistent inbox/dead-letter table.
- No production Docker Compose stack yet.
- No CI quality gate yet.
- No Prometheus/Grafana observability yet.
- Tooling is safer than the initial prototype, but production tool policy still needs a full allow/approval/deny pipeline.

## Production Upgrade Roadmap

The project is being upgraded in staged phases:

1. Engineering baseline, Git, environment template, README cleanup
2. Dependency upgrade and configuration fail-fast checks
3. PostgreSQL, Redis, and database migrations
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
