package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.audit.AdminAuditEvent;
import com.hkdzagent.agent.audit.JdbcAdminAuditRepository;
import com.hkdzagent.agent.console.JdbcToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.im.FeishuResultOutboxMessage;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import com.hkdzagent.agent.im.FeishuInboxEvent;
import com.hkdzagent.agent.im.JdbcFeishuEventInboxRepository;
import com.hkdzagent.agent.im.JdbcFeishuResultOutboxRepository;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres-integration")
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "(?i)true")
class RealPostgresAgentRuntimeIntegrationTest {

    private String schema;
    private JdbcTemplate adminJdbc;
    private DriverManagerDataSource runtimeDataSource;

    @BeforeEach
    void setUp() {
        String host = environment("POSTGRES_HOST", "localhost");
        String port = environment("POSTGRES_PORT", "5432");
        String database = environment("POSTGRES_DB", "xingclaw_agent");
        String username = environment("POSTGRES_USER", "xingclaw_agent");
        String password = environment("POSTGRES_PASSWORD", "xingclaw-local-password");
        String baseUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;

        DriverManagerDataSource adminDataSource = new DriverManagerDataSource(
                baseUrl, username, password);
        adminJdbc = new JdbcTemplate(adminDataSource);
        schema = "runtime_it_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);

        runtimeDataSource = new DriverManagerDataSource(
                baseUrl + "?currentSchema=" + schema, username, password);
        Flyway.configure()
                .dataSource(runtimeDataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration/postgresql")
                .load()
                .migrate();
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && schema != null) {
            adminJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void runFailureCommitsEventAndSafeNotificationAtomically() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRuntimeService runtime = new AgentRuntimeService(
                runtimeRepository(), new AgentRuntimeProperties(), objectMapper, clock);
        AgentFailureService failureService = new AgentFailureService(
                runtime, new FeishuResultOutboxService(outboxRepository(), clock));
        AgentRun created = runtime.create(
                ActorIdentity.feishu("ou_failure_user"), "ou_failure_user",
                "failure-conversation", UUID.randomUUID().toString(),
                "trigger model failure");
        AgentRunClaim claim = runtime.claim(created.runId(), "failure-worker");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));

        transaction.executeWithoutResult(ignored -> failureService.fail(
                created.runId(), claim.run().leaseOwner(), claim.run().leaseEpoch(),
                "api_key=secret-value model unavailable"));

        AgentRun afterRestart = new AgentRuntimeService(
                runtimeRepository(), new AgentRuntimeProperties(), objectMapper, clock)
                .find(created.runId());
        assertThat(afterRestart.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(afterRestart.errorMessage()).contains("model unavailable");
        FeishuResultOutboxMessage notification = outboxRepository()
                .findByDeduplicationKey("run:" + created.runId() + ":run-failed");
        assertThat(notification.type())
                .isEqualTo(FeishuResultOutboxMessage.Type.RUN_FAILED);
        assertThat(notification.text())
                .doesNotContain("secret-value", "api_key");
        assertThat(runtimeRepository().findEventsAfter(created.runId(), 0, 10))
                .extracting(AgentRunEvent::type)
                .contains(AgentRunEventType.RUN_FAILED);
    }

    @Test
    void approvalPauseCommitsRunApprovalEventAndNotificationAtomically() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRuntimeService runtime = new AgentRuntimeService(
                runtimeRepository(), new AgentRuntimeProperties(), objectMapper, clock);
        JdbcToolConfirmationRepository confirmations = approvalRepository();
        FeishuResultOutboxService outboxService = new FeishuResultOutboxService(
                outboxRepository(), clock);
        AgentApprovalPauseService pauseService = new AgentApprovalPauseService(
                new ToolConfirmationService(
                        confirmations, new AgentTraceSanitizer(160),
                        Duration.ofMinutes(15), clock),
                runtime, new AgentTraceSanitizer(160), objectMapper, outboxService);
        AgentRun created = runtime.create(
                ActorIdentity.feishu("ou_postgres_user"), "ou_postgres_user",
                "postgres-conversation", UUID.randomUUID().toString(),
                "run a protected command");
        AgentRunClaim claim = runtime.claim(created.runId(), "postgres-worker");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));

        transaction.executeWithoutResult(ignored -> pauseService.pause(
                claim.run(), claim.run().leaseOwner(), 1,
                "commandExecuteTool", "{\"command\":\"mvn test\"}",
                "{\"schemaVersion\":1,\"toolCall\":{}}"));

        AgentRun afterRestart = new AgentRuntimeService(
                runtimeRepository(), new AgentRuntimeProperties(), objectMapper, clock)
                .find(created.runId());
        assertThat(afterRestart.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(approvalRepository().findById(afterRestart.pendingApprovalId()))
                .isNotNull();
        FeishuResultOutboxMessage notification = outboxRepository()
                .findByDeduplicationKey("run:" + created.runId()
                        + ":approval:" + afterRestart.pendingApprovalId());
        assertThat(notification).isNotNull();
        assertThat(notification.type())
                .isEqualTo(FeishuResultOutboxMessage.Type.APPROVAL_REQUIRED);
        assertThat(runtimeRepository().findEventsAfter(created.runId(), 0, 10))
                .extracting(AgentRunEvent::type)
                .contains(AgentRunEventType.APPROVAL_REQUIRED);
    }

    @Test
    void concurrentRecoveryWorkersClaimExpiredRunOnlyOnce() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JdbcAgentRunRepository setupRepository = runtimeRepository();
        AgentRun stored = setupRepository.create(AgentRun.created(
                UUID.randomUUID().toString(),
                ActorIdentity.user("postgres-recovery-user"),
                "recovery-session",
                "recovery-conversation",
                UUID.randomUUID().toString(),
                "recover after crash",
                5,
                now
        ), "{}");
        AgentRunClaim initial = setupRepository.claim(
                stored.runId(), "initial-worker", now, Duration.ofSeconds(5));
        JdbcAgentRunRepository firstRepository = runtimeRepository();
        JdbcAgentRunRepository secondRepository = runtimeRepository();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AgentRunClaim> first = workers.submit(() -> {
                start.await();
                return firstRepository.claimNextExpired(
                        "recovery-a", now.plusSeconds(6), Duration.ofSeconds(30));
            });
            Future<AgentRunClaim> second = workers.submit(() -> {
                start.await();
                return secondRepository.claimNextExpired(
                        "recovery-b", now.plusSeconds(6), Duration.ofSeconds(30));
            });

            List<AgentRunClaim> successful = java.util.stream.Stream.of(first.get(), second.get())
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(successful).singleElement().satisfies(claim -> {
                assertThat(claim.run().runId()).isEqualTo(stored.runId());
                assertThat(claim.run().leaseEpoch())
                        .isEqualTo(initial.run().leaseEpoch() + 1);
            });
            AgentRun recovered = runtimeRepository().findById(stored.runId());
            assertThat(recovered.leaseOwner())
                    .isIn("recovery-a", "recovery-b");
            setupRepository.appendWorkerEvent(
                    stored.runId(), recovered.leaseOwner(), recovered.leaseEpoch(),
                    AgentRunEventType.TOOL_STARTED, "{}", now.plusSeconds(7));
            setupRepository.appendWorkerEvent(
                    stored.runId(), recovered.leaseOwner(), recovered.leaseEpoch(),
                    AgentRunEventType.RUN_RECOVERY_STARTED, "{}", now.plusSeconds(8));
            assertThat(setupRepository.findRecoveryEvidence(stored.runId()))
                    .isEqualTo(new AgentRunRecoveryEvidence(true, 1));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void persistsFeishuEventRunBindingAndFencesStaleInboxClaim() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AgentRun run = runtimeRepository().create(AgentRun.created(
                UUID.randomUUID().toString(), ActorIdentity.feishu("ou_binding_user"),
                "ou_binding_user", "binding-conversation", UUID.randomUUID().toString(),
                "bind this event", 5, now), "{}");
        JdbcFeishuEventInboxRepository inbox = inboxRepository();
        inbox.receive(new FeishuInboxEvent(
                "event-postgres-binding", "im.message.receive_v1", "ou_binding_user", "{}",
                FeishuInboxEvent.Status.RECEIVED, now,
                null, null, now, 0, null));
        FeishuInboxEvent first = inbox.claim(
                "event-postgres-binding", now, Duration.ofMinutes(5), 3);

        assertThat(inbox.bindRun(
                first.eventId(), first.retryCount(), run.runId())).isTrue();
        FeishuInboxEvent replacement = inbox.claim(
                first.eventId(), now.plusSeconds(300), Duration.ofMinutes(5), 3);

        JdbcFeishuEventInboxRepository afterRestart = inboxRepository();
        assertThat(afterRestart.findById(first.eventId()).runId()).isEqualTo(run.runId());
        assertThat(afterRestart.markProcessed(
                first.eventId(), first.retryCount(), now.plusSeconds(301))).isFalse();
        assertThat(afterRestart.markProcessed(
                replacement.eventId(), replacement.retryCount(), now.plusSeconds(302))).isTrue();
    }

    @Test
    void survivesRepositoryRestartAndResumesApprovalExactlyOnce() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JdbcAgentRunRepository firstProcess = runtimeRepository();
        AgentRun created = firstProcess.create(AgentRun.created(
                UUID.randomUUID().toString(),
                ActorIdentity.user("postgres-integration-user"),
                "postgres-session",
                "postgres-conversation",
                UUID.randomUUID().toString(),
                "run a protected command",
                5,
                now
        ), "{\"source\":\"postgres-integration\"}");
        AgentRunClaim firstClaim = firstProcess.claim(
                created.runId(), "worker-before-restart", now, Duration.ofSeconds(5));
        firstProcess.appendEvent(
                created.runId(), AgentRunEventType.MODEL_STARTED, "{\"step\":1}", now.plusSeconds(1));

        JdbcAgentRunRepository secondProcess = runtimeRepository();
        AgentRun afterRestart = secondProcess.findById(created.runId());
        assertThat(afterRestart.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(afterRestart.version()).isEqualTo(firstClaim.run().version());
        assertThat(afterRestart.lastEventSequence()).isEqualTo(2);
        assertThat(secondProcess.claim(
                created.runId(), "competing-worker", now.plusSeconds(1), Duration.ofSeconds(10))).isNull();

        AgentRunClaim reclaimed = secondProcess.claim(
                created.runId(), "worker-after-expiry", now.plusSeconds(6), Duration.ofSeconds(10));
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.run().leaseOwner()).isEqualTo("worker-after-expiry");

        String approvalId = UUID.randomUUID().toString();
        JdbcToolConfirmationRepository approvals = approvalRepository();
        ToolConfirmation pending = approvals.save(new ToolConfirmation(
                approvalId,
                created.ownerKey(),
                created.sessionId(),
                created.traceId(),
                created.runId(),
                "commandExecuteTool",
                "1.0.0",
                "call-1",
                "a".repeat(64),
                "{\"command\":\"mvn test\"}",
                ToolConfirmation.Status.PENDING,
                null,
                now.plusSeconds(6),
                now.plusSeconds(60),
                null
        ));
        AgentRun waitingCandidate = reclaimed.run().waitForApproval(
                approvalId,
                "{\"schemaVersion\":1,\"toolCall\":{\"id\":\"call-1\"}}",
                "worker-after-expiry",
                reclaimed.run().leaseEpoch(),
                now.plusSeconds(7)
        );
        AgentRun waiting = secondProcess.update(
                waitingCandidate, reclaimed.run().version(), "worker-after-expiry");
        secondProcess.appendEvent(
                created.runId(), AgentRunEventType.APPROVAL_REQUIRED,
                "{\"approvalId\":\"" + approvalId + "\"}", now.plusSeconds(7));

        JdbcAgentRunRepository thirdProcess = runtimeRepository();
        JdbcToolConfirmationRepository approvalsAfterRestart = approvalRepository();
        AgentRun durableWaiting = thirdProcess.findById(created.runId());
        assertThat(durableWaiting.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(durableWaiting.pendingApprovalId()).isEqualTo(approvalId);
        assertThat(durableWaiting.checkpointJson()).contains("call-1");
        assertThat(durableWaiting.leaseOwner()).isNull();
        assertThat(approvalsAfterRestart.findById(approvalId)).isEqualTo(pending);

        ToolConfirmation approved = approvalsAfterRestart.decidePending(
                approvalId, ToolConfirmation.Status.APPROVED, "approved", now.plusSeconds(8));
        assertThat(approved.status()).isEqualTo(ToolConfirmation.Status.APPROVED);
        assertThat(approvalsAfterRestart.decidePending(
                approvalId, ToolConfirmation.Status.APPROVED, "duplicate", now.plusSeconds(9))).isNull();

        AgentRun resumedCandidate = durableWaiting.resumeApproval(
                approvalId, "resume-worker", now.plusSeconds(8), now.plusSeconds(38));
        AgentRun resumed = thirdProcess.update(
                resumedCandidate, durableWaiting.version(), null);
        AgentRun duplicateCandidate = durableWaiting.resumeApproval(
                approvalId, "duplicate-worker", now.plusSeconds(8), now.plusSeconds(38));

        assertThat(resumed.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(resumed.pendingApprovalId()).isNull();
        assertThat(thirdProcess.update(
                duplicateCandidate, durableWaiting.version(), null)).isNull();
        assertThat(thirdProcess.findEventsAfter(created.runId(), 0, 20))
                .extracting(AgentRunEvent::sequence)
                .containsExactly(1L, 2L, 3L);

        JdbcFeishuResultOutboxRepository firstOutbox = outboxRepository();
        FeishuResultOutboxMessage resultMessage = new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), created.runId(), "ou_postgres_user",
                "durable final result", FeishuResultOutboxMessage.Type.FINAL_RESULT,
                "run:" + created.runId() + ":final-result",
                FeishuResultOutboxMessage.Status.PENDING,
                now.plusSeconds(10), null, null, null,
                now.plusSeconds(10), 0, null);
        assertThat(firstOutbox.enqueue(resultMessage)).isTrue();

        JdbcFeishuResultOutboxRepository outboxAfterRestart = outboxRepository();
        assertThat(outboxAfterRestart.findByDeduplicationKey(
                resultMessage.deduplicationKey())).isEqualTo(resultMessage);
        assertThat(outboxAfterRestart.enqueue(new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), created.runId(), "ou_postgres_user",
                "duplicate", FeishuResultOutboxMessage.Type.FINAL_RESULT,
                resultMessage.deduplicationKey(), FeishuResultOutboxMessage.Status.PENDING,
                now.plusSeconds(11), null, null, null,
                now.plusSeconds(11), 0, null))).isFalse();
        FeishuResultOutboxMessage approvalMessage = new FeishuResultOutboxMessage(
                UUID.randomUUID().toString(), created.runId(), "ou_postgres_user",
                "approval required", FeishuResultOutboxMessage.Type.APPROVAL_REQUIRED,
                "run:" + created.runId() + ":approval:" + approvalId,
                FeishuResultOutboxMessage.Status.PENDING,
                now.plusSeconds(11), null, null, null,
                now.plusSeconds(11), 0, null);
        assertThat(outboxAfterRestart.enqueue(approvalMessage)).isTrue();
        assertThat(outboxAfterRestart.findByDeduplicationKey(
                approvalMessage.deduplicationKey())).isEqualTo(approvalMessage);
        outboxAfterRestart.claim(
                resultMessage.id(), now.plusSeconds(10), Duration.ofMinutes(5), 1);
        assertThat(outboxAfterRestart.deadLetterExhaustedStale(
                now.plusSeconds(310), Duration.ofMinutes(5), 1)).isOne();
        assertThat(outboxAfterRestart.findById(resultMessage.id()).status())
                .isEqualTo(FeishuResultOutboxMessage.Status.DEAD);
        assertThat(outboxAfterRestart.countByStatus(
                FeishuResultOutboxMessage.Status.DEAD)).isOne();
        FeishuResultOutboxMessage manuallyRetried = outboxAfterRestart.retryDead(
                resultMessage.id(), now.plusSeconds(311));
        assertThat(manuallyRetried.status())
                .isEqualTo(FeishuResultOutboxMessage.Status.RETRYABLE);
        assertThat(manuallyRetried.attemptCount()).isZero();
        assertThat(outboxAfterRestart.countReady(
                now.plusSeconds(311), Duration.ofMinutes(5), 5)).isEqualTo(2);
        assertThat(outboxAfterRestart.retryDead(
                resultMessage.id(), now.plusSeconds(312))).isNull();

        AdminAuditEvent auditEvent = new AdminAuditEvent(
                UUID.randomUUID().toString(), "user:postgres-admin",
                "FEISHU_OUTBOX_RETRY", "FEISHU_RESULT_OUTBOX",
                resultMessage.id(), AdminAuditEvent.Outcome.SUCCEEDED,
                "manual retry", now.plusSeconds(312));
        assertThat(auditRepository().save(auditEvent)).isEqualTo(auditEvent);
        assertThat(auditRepository().findRecent(10)).containsExactly(auditEvent);
        assertThat(new JdbcTemplate(runtimeDataSource).queryForObject(
                "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success",
                Integer.class
        )).isGreaterThanOrEqualTo(14);
        assertThat(waiting).isNotNull();
    }

    private JdbcAgentRunRepository runtimeRepository() {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(runtimeDataSource);
        return new JdbcAgentRunRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(runtimeDataSource))
        );
    }

    private JdbcToolConfirmationRepository approvalRepository() {
        return new JdbcToolConfirmationRepository(
                new NamedParameterJdbcTemplate(runtimeDataSource), new ObjectMapper());
    }

    private JdbcFeishuResultOutboxRepository outboxRepository() {
        return new JdbcFeishuResultOutboxRepository(
                new NamedParameterJdbcTemplate(runtimeDataSource));
    }

    private JdbcFeishuEventInboxRepository inboxRepository() {
        return new JdbcFeishuEventInboxRepository(
                new NamedParameterJdbcTemplate(runtimeDataSource), new ObjectMapper());
    }

    private JdbcAdminAuditRepository auditRepository() {
        return new JdbcAdminAuditRepository(
                new NamedParameterJdbcTemplate(runtimeDataSource));
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
