# Operations Runbook

This runbook covers the current production-designed repository. It is usable for local drills and as a deployment handoff checklist, but it does not imply that the project has been production-deployed. Deployment automation, complete Runtime metrics, alerting, backups, and graceful HTTP draining are not implemented in this repository.

## Operating principles

- Use the `prod` Spring profile for any production-like deployment. Do not bypass `ProductionConfigurationValidator` startup failures.
- Use JDBC for every durable repository. A production instance must never fall back to memory or file storage after a database failure.
- Treat `agent_runs`, `agent_run_events`, `tool_approvals`, `tool_execution_journal`, Feishu inbox/outbox, traces, memory, users, and audit rows as one durable system.
- Prefer owner-scoped APIs and ADMIN APIs for state changes. Use SQL below for diagnosis only unless an incident procedure explicitly says otherwise.
- Never automatically retry a `STARTED` tool journal row. The external side effect is uncertain.
- Never expose Runtime checkpoints in tickets or logs; they can contain complete provider messages and tool arguments.

## Production-like startup checklist

### Required configuration

Set `SPRING_PROFILES_ACTIVE=prod` and provide non-placeholder values for the model provider, Feishu, Tavily, PostgreSQL, Redis, and workspace root. At minimum, the following safety switches must also be set:

```properties
SPRING_FLYWAY_ENABLED=true
AGENT_RUNTIME_REPOSITORY=jdbc
AGENT_TRACE_REPOSITORY=jdbc
AGENT_MEMORY_REPOSITORY=jdbc
AGENT_TOOL_APPROVAL_REPOSITORY=jdbc
FEISHU_INBOX_REPOSITORY=jdbc
FEISHU_OUTBOX_REPOSITORY=jdbc
AGENT_AUDIT_REPOSITORY=jdbc
AGENT_SECURITY_ENABLED=true
AGENT_SECURITY_USER_REPOSITORY=jdbc
```

`AGENT_SECURITY_JWT_SECRET` must contain at least 32 UTF-8 bytes. `AGENT_WORKSPACE_ROOT` must be an explicit deployment path rather than `.`, `workspace`, or `./workspace`.

The validator also requires explicit provider credentials, Feishu verification/encryption values, datasource credentials, and a Redis password. Redis is provisioned and validated but is not currently used by application logic.

### Infrastructure and migrations

For a local drill only:

```powershell
docker compose up -d postgres redis
docker compose ps
```

Expected result: both services report `healthy`. The Compose credentials are development-only and must not be reused in a deployment.

Run the quality gates before packaging:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
```

Start the packaged application with the `prod` profile. Flyway must reach the latest migration before the instance accepts traffic. A production validator error is a failed deployment, not a warning to suppress.

### Initial health and authentication checks

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Expected response status is `UP`. Health details are intentionally hidden.

Authenticate with a provisioned account; do not put a real password into shell history:

```powershell
$login = Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/auth/login `
  -ContentType application/json `
  -Body (@{ username = $env:AGENT_OPERATOR_USER; password = $env:AGENT_OPERATOR_PASSWORD } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod -Headers $headers http://localhost:8080/api/auth/me
```

An operator performing outbox retries or approval decisions needs the `ADMIN` role.

## Routine checks

### Application and database

- `GET /actuator/health` must be `UP`.
- Confirm PostgreSQL connections are available and Flyway is at the expected version.
- Confirm the application started with JDBC repositories; startup under `prod` fails if any required durable repository is not JDBC.
- Review application logs for repeated lease loss, recovery blocking, provider timeout, or outbox delivery errors. Do not log checkpoints, tokens, message text, or tool arguments during investigation.

Useful read-only SQL:

```sql
SELECT status, count(*)
FROM agent_runs
GROUP BY status
ORDER BY status;

SELECT id, owner_key, status, pending_approval_id,
       lease_owner, lease_expires_at, updated_at
FROM agent_runs
WHERE status IN ('RUNNING', 'WAITING_APPROVAL')
ORDER BY updated_at;

SELECT run_id, sequence, event_type, created_at
FROM agent_run_events
WHERE run_id = :run_id
ORDER BY sequence;
```

Do not select `checkpoint`, `user_message`, event payloads, or tool result messages unless the incident requires them and the output has an approved secure destination.

### Feishu notification outbox

Metrics are exposed under:

```text
xingclaw.feishu.outbox.messages{status=...}
xingclaw.feishu.outbox.ready
xingclaw.feishu.outbox.oldest.outstanding.age.seconds
```

ADMIN inspection APIs:

```powershell
Invoke-RestMethod -Headers $headers `
  http://localhost:8080/api/agent/admin/feishu-outbox/summary

Invoke-RestMethod -Headers $headers `
  "http://localhost:8080/api/agent/admin/feishu-outbox/messages?status=DEAD&limit=20"
```

The API masks recipient identifiers. Any non-zero `DEAD` count requires investigation. A sustained `ready` count or increasing oldest-outstanding age indicates delivery is not keeping up. The repository does not define production alert thresholds; set them from measured traffic and the configured retry/poll intervals.

## Incident procedures

### Application will not start under `prod`

1. Read the complete `Unsafe production configuration` list.
2. Supply every missing or placeholder value through the deployment secret/configuration system.
3. Confirm all durable repository switches are `jdbc`, Flyway is enabled, authentication is enabled, and the JWT secret is at least 32 bytes.
4. Confirm `AGENT_WORKSPACE_ROOT` resolves to the intended isolated directory.
5. Restart the application. Do not remove `prod` from the active profiles as a workaround.

### PostgreSQL unavailable

1. Stop new Agent traffic. Do not start an instance configured with memory repositories.
2. Preserve the PostgreSQL data volume and collect database/container logs.
3. Restore database availability and confirm the health check passes.
4. Restart or reconnect the application, then inspect non-terminal Runs and outbox backlog.
5. Allow the recovery schedulers to process expired leases. Do not directly change Run status or delete journal rows.

For a local recovery drill, `docker compose stop postgres` followed by `docker compose start postgres` preserves the volume. Never use `docker compose down -v` when testing persistence or responding to an incident.

### Run remains `RUNNING` after a Worker failure

The scheduler checks every `AGENT_RUNTIME_RECOVERY_INTERVAL` and can claim a Run after its lease expires. Recovery is bounded by `AGENT_RUNTIME_MAX_RECOVERY_ATTEMPTS`.

1. Check `lease_expires_at`; do not intervene before the lease expires unless the owning process is known dead.
2. Inspect ordered events for `RUN_RECOVERY_STARTED` or `RUN_RECOVERY_BLOCKED`.
3. Inspect the tool journal summary for the Run.
4. Model-only work may restart. Tool activity is handled conservatively as described below.
5. If attempts are exhausted, retain the Run/events for investigation instead of resetting the version or lease manually.

### Recovery blocked by tool evidence

Read-only diagnosis:

```sql
SELECT run_id, tool_call_id, tool_name, tool_version, arguments_hash,
       status, result_status, started_at, completed_at
FROM tool_execution_journal
WHERE run_id = :run_id
ORDER BY started_at;
```

- `STARTED`: the external effect may or may not have happened. Do not retry automatically. Reconcile with the target system using its audit log or idempotency key.
- `COMPLETED`: the durable result exists, but automatic model continuation remains blocked because a complete post-tool continuation checkpoint is not persisted.
- `TOOL_STARTED` Runtime event without a journal row: treat as inconsistent legacy evidence and investigate manually.

Do not delete or rewrite the journal to force recovery. That destroys the duplicate-submission fence.

### Cancel a Run

```powershell
$body = @{ reason = "operator cancellation" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -Headers $headers `
  -ContentType application/json `
  -Body $body `
  "http://localhost:8080/api/agent/runs/$runId/cancel"
```

- `200` with `newlyCancelled=true`: cancellation committed.
- `200` with `newlyCancelled=false`: the Run was already cancelled.
- `404`: the Run does not exist for this owner; another owner's existence is not disclosed.
- `409`: the Run already completed/failed or a concurrent terminal transition won.

Cancellation prevents later model rounds and tool starts. If a tool already crossed the atomic start gate, its result is still persisted because an external effect may already exist. Do not promise that cancellation retracts that effect.

### Approval is stuck or returns conflict

```sql
SELECT r.id AS run_id, r.status AS run_status, r.pending_approval_id,
       a.status AS approval_status, a.expires_at, a.decided_at
FROM agent_runs r
LEFT JOIN tool_approvals a ON a.id = r.pending_approval_id
WHERE r.id = :run_id;
```

- `WAITING_APPROVAL` + `PENDING`: an ADMIN may approve or reject through the API.
- Expired approval: the reconciler terminates the waiting Run; do not approve by SQL.
- `CANCELLED`: a late approval conflict is expected.
- Approval `APPROVED` with Run `CANCELLED`: approval won its transaction, then cancellation committed before tool start. This is a valid race result.
- Approval decided while the Run still waits: check the approval recovery scheduler and application logs.

Do not update approval status directly. Approval and cancellation depend on a Run-first row-lock order that ad hoc SQL can violate.

### Feishu outbox message is `DEAD`

1. Inspect the masked ADMIN message view and application logs.
2. Correct the Feishu credential, network, permission, or recipient problem.
3. Retry only a `DEAD` message:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Headers $headers `
  "http://localhost:8080/api/agent/admin/feishu-outbox/$messageId/retry"
```

The retry resets delivery attempts and writes an administrator audit event. `404` means the message does not exist; `409` means it is not currently `DEAD`. Delivery remains at-least-once: a crash after Feishu accepts a message but before `SENT` is persisted can produce a duplicate.

## Safe application shutdown

The repository does not configure HTTP graceful draining. Use the deployment platform to stop new traffic before terminating a process.

1. Remove the instance from routing or stop new Agent requests.
2. Inspect `RUNNING` rows owned by active leases. Wait for short work where practical.
3. Cancel owner-authorized Runs through the API when the requested operation should not continue.
4. Stop the application process without deleting PostgreSQL or Redis volumes.
5. On restart, verify health, non-terminal Runs, recovery events, and outbox backlog.

If the process is killed while a tool journal row is `STARTED`, automatic replay is intentionally blocked. This is safer than assuming the external call did not happen.

## Deployment rollback and migration safety

- Flyway migrations are forward migrations; this repository does not provide automated down migrations.
- Back up and test restore procedures before applying schema changes to persistent environments.
- Confirm an older application version is compatible with the migrated schema before rolling application code back.
- Do not edit `flyway_schema_history` to force a rollback.
- The repository has CI quality gates but no image-build, release, deployment, backup, or rollback automation.

## Incident evidence checklist

Capture the minimum evidence needed for reconstruction:

- application version/commit and active profile
- incident time window and affected Run IDs
- sanitized Run status, version, lease owner/epoch, and event types/sequences
- approval status and timestamps
- tool journal status and external-system reconciliation result
- Feishu outbox status/attempt count and masked ADMIN view
- PostgreSQL health, restart time, and relevant database logs

Do not attach JWTs, provider/Feishu credentials, checkpoints, raw user messages, tool arguments, recipient IDs, or unsanitized external responses.

## Verification drills

Use the documented gates rather than inventing ad hoc recovery mutations:

- [Quality gates](quality-gates.md): default Maven tests, PostgreSQL integration, and real database-process restart procedure.
- [Agent Runtime](agent-runtime.md): state, lease, approval, cancellation, and recovery contracts.
- [Tool execution journal](tool-execution-journal.md): uncertain-effect decision table.
- [Infrastructure](infrastructure.md): repository switches, Flyway, inbox/outbox, and persistence boundaries.
