# Resume and Interview Notes

## Objective positioning

Current verdict: this is a resume-ready, production-oriented engineering project rather than a toy demo. It has durable state, concurrency control, failure recovery, security boundaries, external-channel reliability, real PostgreSQL verification, and an operations runbook.

It is not production-complete or production-deployed. The strongest honest description is “production-designed” or “production-oriented.” Missing evidence includes load/capacity testing, complete Runtime metrics and SLOs, checkpoint encryption/retention, deployment and backup automation, pgvector retrieval, and automatic post-tool model continuation.

## Recommended Chinese resume entry

### XingClaw AI Agent 平台

技术栈：Java 17、Spring Boot 3.5、Spring AI、PostgreSQL 17、Flyway、Spring Security、Reactor SSE、Docker Compose、GitHub Actions（JDK 21 CI）

项目简介：面向长任务和敏感工具调用的生产化设计 AI Agent 平台，提供持久化执行状态、人机审批、协作式取消、故障恢复、多用户隔离和飞书可靠消息处理。

- 设计 PostgreSQL 持久化 Agent Runtime 状态机，引入乐观锁、Worker 租约/心跳、lease epoch fencing、顺序事件日志和 SSE 断点重放，支持进程退出后的运行状态重建及过期任务竞争恢复。
- 实现 Human-in-the-loop 工具审批与 owner-scoped 幂等取消：事务化保存模型 checkpoint 和待审批记录，统一“Run 行锁 → Approval 行锁”顺序，并通过原子 Run lease/tool journal 启动门阻止取消后的工具执行。
- 构建以 `(runId, toolCallId)` 为稳定身份的工具执行 journal，支持参数/版本绑定、execution token 完成围栏和终态结果重放；对崩溃后 `STARTED` 等不确定外部副作用采用保守阻断，避免错误自动重试。
- 完成 JWT、`USER`/`ADMIN` RBAC、资源 owner 隔离，以及飞书 PostgreSQL Inbox/Outbox 的去重、租约、重试、死信和审计重放；使用 15 个 Flyway 迁移、300+ 自动化测试及 PostgreSQL 17 真实并发/进程重启门禁验证关键一致性。

## Recommended English resume entry

### XingClaw AI Agent Platform

Tech: Java 17, Spring Boot 3.5, Spring AI, PostgreSQL 17, Flyway, Spring Security, Reactor SSE, Docker Compose, GitHub Actions (JDK 21 CI)

Built a production-designed AI Agent platform for durable long-running execution and safety-gated tool use, with human approval, cooperative cancellation, crash recovery, multi-user isolation, and reliable Feishu integration.

- Designed a PostgreSQL-backed Agent Runtime state machine with optimistic locking, Worker leases/heartbeats, lease-epoch fencing, an ordered event log, and replayable SSE IDs for restart-safe execution and competing stale-run recovery.
- Implemented restart-recoverable human approval and owner-scoped idempotent cancellation, using transactional checkpoints, a consistent Run-to-Approval row-lock order, and an atomic Run-lease/tool-journal start gate to prevent tools from starting after cancellation.
- Built a tool-execution journal keyed by `(runId, toolCallId)` with argument/version binding, execution-token completion fencing, and terminal-result replay; conservatively blocked uncertain external effects after crashes instead of unsafe automatic retries.
- Added JWT/RBAC and owner isolation plus PostgreSQL Feishu Inbox/Outbox deduplication, leases, retries, dead letters, and audited replay; verified critical behavior with 15 Flyway migrations, 300+ automated tests, PostgreSQL 17 lock races, and a real database-process restart drill.

## Why these bullets work

Each bullet contains three parts:

1. the engineering problem: durable execution, concurrent decisions, uncertain effects, or external-channel reliability;
2. the design choice: state machine, fencing, lock order, journal, inbox/outbox, or RBAC;
3. the evidence: PostgreSQL constraints, deterministic races, migrations, tests, or restart recovery.

Do not add every tool or endpoint to the resume. The project is differentiated by consistency and failure handling, not by the number of integrations.

## Ninety-second interview walkthrough

1. “The project started as a synchronous tool-calling demo. I moved execution into a durable Agent Runtime so an HTTP connection or process lifetime no longer owns the task.”
2. “A Run is a PostgreSQL aggregate with a state machine, optimistic version, Worker lease/epoch, checkpoint, and ordered events. SSE clients replay those events by sequence.”
3. “Sensitive tools transition the Run to `WAITING_APPROVAL` before invocation. Approval and cancellation both lock the Run first, so concurrent decisions produce one of two explicit, tested outcomes rather than contradictory rows.”
4. “Immediately before a tool call, an atomic gate checks the active Run lease and reserves a journal identity. Cancellation before the gate prevents execution; after the gate, the result is persisted because the side effect may already exist.”
5. “Recovery is deliberately classified. Model-only work can restart within a budget, but uncertain or completed tool effects without a full model continuation checkpoint are blocked for investigation.”
6. “I verified this with deterministic H2 and PostgreSQL lock races, 15 Flyway migrations, and a two-phase test that restarts the PostgreSQL process and proves state and duplicate-submission fences survive.”
7. “I describe it as production-designed, not production-deployed, because load evidence, full observability, encrypted checkpoints, and deployment automation are still missing.”

## Evidence snapshot

Latest local hardening verification:

- 80 Java test source files.
- 15 PostgreSQL Flyway migrations.
- Default Maven suite: 305 discovered, 296 executed, 9 environment-gated PostgreSQL cases skipped.
- Real PostgreSQL 17 Runtime integration: 8/8 executed successfully.
- Real PostgreSQL process restart: seed 1/1 and verify 1/1 executed successfully.
- Operations runbook covers startup, diagnosis, incident handling, safe shutdown, and recovery boundaries.

Key evidence locations:

| Claim | Implementation | Verification |
|---|---|---|
| Durable state machine and leases | [`AgentRun`](../src/main/java/com/hkdzagent/agent/runtime/AgentRun.java), [`JdbcAgentRunRepository`](../src/main/java/com/hkdzagent/agent/runtime/JdbcAgentRunRepository.java) | [`AgentRunFencingContractTest`](../src/test/java/com/hkdzagent/agent/runtime/AgentRunFencingContractTest.java), [`RealPostgresAgentRuntimeIntegrationTest`](../src/test/java/com/hkdzagent/agent/runtime/RealPostgresAgentRuntimeIntegrationTest.java) |
| Approval/cancellation serialization | [`AgentApprovalOrchestrator`](../src/main/java/com/hkdzagent/agent/runtime/AgentApprovalOrchestrator.java), [`AgentCancellationService`](../src/main/java/com/hkdzagent/agent/runtime/AgentCancellationService.java) | [`AgentApprovalCancellationRaceTest`](../src/test/java/com/hkdzagent/agent/runtime/AgentApprovalCancellationRaceTest.java), PostgreSQL race cases |
| Atomic tool-start fence | [`RunFencedToolExecutionStartGate`](../src/main/java/com/hkdzagent/agent/runtime/RunFencedToolExecutionStartGate.java) | [`RunFencedToolExecutionStartGateTest`](../src/test/java/com/hkdzagent/agent/runtime/RunFencedToolExecutionStartGateTest.java), [`AgentRuntimePipelineExecutionTest`](../src/test/java/com/hkdzagent/agent/runtime/AgentRuntimePipelineExecutionTest.java) |
| Journal-aware recovery | [`AgentRunRecoveryService`](../src/main/java/com/hkdzagent/agent/runtime/AgentRunRecoveryService.java), [`JdbcToolExecutionJournalRepository`](../src/main/java/com/hkdzagent/agent/tool/JdbcToolExecutionJournalRepository.java) | [`AgentRunRecoveryServiceTest`](../src/test/java/com/hkdzagent/agent/runtime/AgentRunRecoveryServiceTest.java), [`RealPostgresContainerRestartRecoveryTest`](../src/test/java/com/hkdzagent/agent/runtime/RealPostgresContainerRestartRecoveryTest.java) |
| Owner isolation and RBAC | [`SecurityConfiguration`](../src/main/java/com/hkdzagent/agent/security/SecurityConfiguration.java) | [`ObjectAuthorizationFunctionalTest`](../src/test/java/com/hkdzagent/agent/security/ObjectAuthorizationFunctionalTest.java), [`SecurityFunctionalTest`](../src/test/java/com/hkdzagent/agent/security/SecurityFunctionalTest.java) |
| Feishu Inbox/Outbox reliability | [`FeishuEventProcessor`](../src/main/java/com/hkdzagent/agent/im/FeishuEventProcessor.java), [`FeishuResultOutboxScheduler`](../src/main/java/com/hkdzagent/agent/im/FeishuResultOutboxScheduler.java) | [`AgentFeishuWebhookFunctionalTest`](../src/test/java/com/hkdzagent/agent/functional/AgentFeishuWebhookFunctionalTest.java), [`FeishuResultOutboxTest`](../src/test/java/com/hkdzagent/agent/im/FeishuResultOutboxTest.java) |

## High-frequency interview questions

### Why not keep the task in the HTTP request?

The request lifecycle cannot safely own a long-running Agent task. Runtime persistence separates execution from a client connection, gives every transition a durable version/event, and lets a new Worker reconstruct or classify work after process failure.

### Why use both optimistic versions and Worker leases?

They protect different races. `version` prevents a stale snapshot from overwriting newer state. The Worker lease and monotonically increasing epoch determine which process is allowed to emit Worker events or advance execution over time.

### How do approval and cancellation avoid a deadlock or split-brain result?

Both acquire resources in the same order: Run row, then approval row. Approval proceeds only if the locked Run is still `WAITING_APPROVAL` for that exact approval ID. Cancellation that wins closes the pending approval; a late approval returns conflict. Both lock-winning branches are tested on PostgreSQL 17.

### Does cancellation terminate an in-flight external call?

Not universally. It is a durable cooperative transition checked between model rounds and at the atomic tool-start boundary. If a tool already crossed that boundary, the system persists its result because an external side effect may already have occurred.

### Is the tool journal exactly-once execution?

It provides one local durable reservation for a stable tool-call identity and prevents a known call from being submitted again after completion or an uncertain crash. It cannot atomically commit PostgreSQL state with an arbitrary external API. Strict external exactly-once requires a target-provided idempotency contract or a distributed transactional protocol.

### Why block recovery after a completed tool?

The tool result is durable, but the project does not persist a complete provider continuation checkpoint after every ordinary tool round. Re-running the original request could repeat planning or another effect; inventing a continuation could corrupt the conversation. The safe current policy is to fail with `RUN_RECOVERY_BLOCKED` and preserve evidence.

### What would you build next for a real deployment?

Prioritize checkpoint encryption/retention, complete Runtime/model/tool metrics and SLOs, load and chaos testing, graceful draining, backup/restore and deployment automation, token-event batching, provider rate limiting/circuit breaking, and a complete post-tool continuation checkpoint. Product-dependent work includes pgvector retrieval and tenant-level policy administration.

## Claims to avoid

- Do not call the system production-deployed or a complete production platform.
- Do not claim arbitrary external exactly-once effects.
- Do not claim that cancellation forcibly interrupts every blocking call or reverses an external side effect.
- Do not claim automatic recovery after every tool execution; explain the classified recovery boundary.
- Do not claim encrypted checkpoints, token batching, pgvector hybrid retrieval, complete metrics/SLOs, deployment automation, or proven load capacity.
- Do not convert “300+ automated tests” into a coverage percentage; no coverage threshold is configured.
