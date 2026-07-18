# Infrastructure

This phase introduces the baseline infrastructure for durable state, but the current application code still uses some prototype storage adapters such as JSONL chat memory and in-memory trace state.

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

The initial migration creates the future durable-state tables for:

- conversations
- messages
- agent trace events
- tool approvals
- Feishu event inbox

These tables are not fully wired into runtime code yet. Wiring them into repositories and replacing prototype storage is planned for later Phase 2 work.

## Current safety boundary

The local default passwords in `.env.example` and `docker-compose.yml` are only for development. Production must provide explicit database and Redis credentials through environment variables or a secret manager.
