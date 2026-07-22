# Quality Gates

This project is a production-designed resume project that is still being hardened for deployment.
Every pull request or push to `main` / `master` must pass `.github/workflows/ci.yml`.
These jobs form the minimum merge gate for the current project phase.

## Required CI jobs

### Backend Maven verification

The baseline job runs the complete default test suite and packages the application:

```bash
./mvnw -B --no-transfer-progress test
./mvnw -B --no-transfer-progress -DskipTests package
```

### PostgreSQL runtime integration

The database job starts a PostgreSQL 17 service container with a health check and then:

1. Applies all available Flyway migrations (currently V1-V14) to an isolated schema.
2. Verifies durable Agent Run, lease fencing and expiry, concurrent stale-run claiming, classified recovery, checkpoint, event ordering, approval recovery, and Feishu approval/final/failure notification-outbox persistence, statistics, dead-letter, and manual-retry behavior.
3. Seeds a Run in `WAITING_APPROVAL` and records `pg_postmaster_start_time()`.
4. Restarts the actual PostgreSQL service process.
5. Verifies that state survived the restart and that approval/resume remain exactly-once.

The CI credentials are fixed test-only values scoped to the ephemeral service container. The workflow
does not require repository secrets.

## Local PostgreSQL gates

Start the Compose PostgreSQL service before running these commands.

Repository reconstruction and persistence test:

```powershell
$env:RUN_POSTGRES_INTEGRATION_TESTS = "true"
.\mvnw.cmd -Dtest=RealPostgresAgentRuntimeIntegrationTest test
```

Real PostgreSQL process restart test:

```powershell
$env:RUN_POSTGRES_INTEGRATION_TESTS = "true"
$env:POSTGRES_RECOVERY_PHASE = "seed"
.\mvnw.cmd -Dtest=RealPostgresContainerRestartRecoveryTest test

docker compose stop postgres
docker compose start postgres
docker compose ps postgres

$env:POSTGRES_RECOVERY_PHASE = "verify"
.\mvnw.cmd -Dtest=RealPostgresContainerRestartRecoveryTest test
```

The `verify` phase removes the dedicated `runtime_restart_it` schema. Do not use
`docker compose down -v` between phases because deleting the volume would invalidate the persistence proof.

## Current scope

- Java runtime: Temurin JDK 21 on a GitHub-hosted Ubuntu runner
- Dependency cache: Maven cache through `actions/setup-java`
- PostgreSQL: version 17 service container with real migration and restart recovery gates
- Permissions: read-only repository contents
- Triggers: push, pull request, and manual workflow dispatch

## Not yet covered

The current workflow is a CI quality gate, not a complete production CI/CD pipeline. Remaining work includes:

- Redis integration tests
- Static analysis and formatting checks
- Coverage threshold
- Docker image build
- Frontend build and browser end-to-end tests
- Security scanning
- Deployment or release automation

Do not describe this project as having a complete CI/CD pipeline until those gaps are addressed.
