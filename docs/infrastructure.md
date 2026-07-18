# Infrastructure

This phase introduces baseline durable infrastructure. Agent trace and chat memory can now use PostgreSQL through JDBC repositories, while some runtime state still uses prototype adapters such as in-memory tool approval state.

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

The defaults remain `AGENT_TRACE_REPOSITORY=memory` and `AGENT_MEMORY_REPOSITORY=file` so local tests and development startup do not require a running database. Tool approval and Feishu event inbox wiring remain planned Phase 2 work.

## Current safety boundary

The local default passwords in `.env.example` and `docker-compose.yml` are only for development. Production must provide explicit database and Redis credentials through environment variables or a secret manager. Production also must enable Flyway and set `AGENT_TRACE_REPOSITORY=jdbc` and `AGENT_MEMORY_REPOSITORY=jdbc`.
