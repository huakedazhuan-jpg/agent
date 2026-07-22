package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.JdbcToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.console.ToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.trace.InMemoryAgentTraceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentApprovalCancellationRaceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RaceConfig.class);

    @Test
    void cancellationHoldingRunLockMakesConcurrentApprovalLose() {
        contextRunner.run(context -> {
            createTables(new JdbcTemplate(context.getBean(DataSource.class)));
            Scenario scenario = createWaitingScenario(context);
            BlockingToolConfirmationRepository repository =
                    context.getBean(BlockingToolConfirmationRepository.class);
            repository.blockNext(ToolConfirmation.Status.CANCELLED);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<AgentRunCancellation> cancellation = workers.submit(() ->
                        scenario.cancellationService.cancel(
                                scenario.owner, scenario.run.runId(), "cancel wins"));
                repository.awaitBlocked();

                Future<ToolConfirmation> approval = workers.submit(() -> {
                    ToolConfirmation decided = scenario.runtime.decideWaitingApproval(
                            scenario.run.runId(), scenario.approval.id(),
                            () -> scenario.confirmationService.approve(scenario.approval.id()));
                    if (decided == null) {
                        throw new AgentApprovalConflictException("run no longer waits for approval");
                    }
                    return decided;
                });

                assertStillBlocked(approval);
                repository.releaseBlockedDecision();

                assertThat(cancellation.get(5, TimeUnit.SECONDS).outcome())
                        .isEqualTo(AgentRunCancellation.Outcome.CANCELLED);
                assertThatThrownBy(() -> approval.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(AgentApprovalConflictException.class);
                assertThat(scenario.runtime.find(scenario.run.runId()).status())
                        .isEqualTo(AgentRunStatus.CANCELLED);
                assertThat(scenario.confirmationService.findById(scenario.approval.id()).status())
                        .isEqualTo(ToolConfirmation.Status.CANCELLED);
            } finally {
                repository.releaseBlockedDecision();
                workers.shutdownNow();
            }
        });
    }

    @Test
    void approvalHoldingRunLockCommitsBeforeConcurrentCancellation() {
        contextRunner.run(context -> {
            createTables(new JdbcTemplate(context.getBean(DataSource.class)));
            Scenario scenario = createWaitingScenario(context);
            BlockingToolConfirmationRepository repository =
                    context.getBean(BlockingToolConfirmationRepository.class);
            repository.blockNext(ToolConfirmation.Status.APPROVED);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<ToolConfirmation> approval = workers.submit(() ->
                        scenario.runtime.decideWaitingApproval(
                                scenario.run.runId(), scenario.approval.id(),
                                () -> scenario.confirmationService.approve(scenario.approval.id())));
                repository.awaitBlocked();

                Future<AgentRunCancellation> cancellation = workers.submit(() ->
                        scenario.cancellationService.cancel(
                                scenario.owner, scenario.run.runId(), "cancel after approval"));

                assertStillBlocked(cancellation);
                repository.releaseBlockedDecision();

                assertThat(approval.get(5, TimeUnit.SECONDS).status())
                        .isEqualTo(ToolConfirmation.Status.APPROVED);
                assertThat(cancellation.get(5, TimeUnit.SECONDS).outcome())
                        .isEqualTo(AgentRunCancellation.Outcome.CANCELLED);
                assertThat(scenario.runtime.find(scenario.run.runId()).status())
                        .isEqualTo(AgentRunStatus.CANCELLED);
                assertThat(scenario.confirmationService.findById(scenario.approval.id()).status())
                        .isEqualTo(ToolConfirmation.Status.APPROVED);
            } finally {
                repository.releaseBlockedDecision();
                workers.shutdownNow();
            }
        });
    }

    private Scenario createWaitingScenario(
            org.springframework.context.ApplicationContext context
    ) {
        AgentRuntimeService runtime = context.getBean(AgentRuntimeService.class);
        ToolConfirmationService confirmations = context.getBean(ToolConfirmationService.class);
        ActorIdentity owner = ActorIdentity.user("approval-cancel-race-user");
        AgentRun run = runtime.create(
                owner, "race-session", "race-conversation",
                UUID.randomUUID().toString(), "run command");
        AgentRunClaim claim = runtime.claim(run.runId(), "race-worker");
        ToolConfirmation approval = confirmations.requestConfirmationForRun(
                owner, run.sessionId(), run.traceId(), run.runId(),
                "commandExecuteTool", "{\"command\":\"mvn test\"}");
        runtime.waitForApproval(
                run.runId(), claim.run().leaseOwner(), claim.run().leaseEpoch(),
                approval.id(), "{\"step\":1}");
        return new Scenario(
                owner, run, approval, runtime, confirmations,
                context.getBean(AgentCancellationService.class));
    }

    private void assertStillBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_runs (
                    id UUID PRIMARY KEY, owner_key VARCHAR(320) NOT NULL,
                    session_id VARCHAR(256) NOT NULL, conversation_id VARCHAR(1024) NOT NULL,
                    trace_id VARCHAR(128) NOT NULL UNIQUE, user_message CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL, current_step INTEGER NOT NULL,
                    max_steps INTEGER NOT NULL, version BIGINT NOT NULL,
                    last_event_sequence BIGINT NOT NULL, checkpoint JSON NOT NULL,
                    pending_approval_id UUID, final_answer CLOB, error_message CLOB,
                    lease_owner VARCHAR(128), lease_expires_at TIMESTAMP,
                    lease_epoch BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_run_events (
                    id UUID PRIMARY KEY, run_id UUID NOT NULL, sequence BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL, payload JSON NOT NULL,
                    created_at TIMESTAMP NOT NULL, UNIQUE (run_id, sequence),
                    FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE tool_approvals (
                    id UUID PRIMARY KEY, owner_key VARCHAR(320) NOT NULL,
                    session_id VARCHAR(256) NOT NULL, trace_id VARCHAR(128), run_id UUID,
                    tool_name VARCHAR(128) NOT NULL, tool_version VARCHAR(64) NOT NULL,
                    tool_call_id VARCHAR(128), request_hash VARCHAR(128) NOT NULL,
                    request_payload JSON NOT NULL, arguments_preview CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL, decision_reason CLOB,
                    expires_at TIMESTAMP NOT NULL, decided_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL
                )
                """);
    }

    private record Scenario(
            ActorIdentity owner,
            AgentRun run,
            ToolConfirmation approval,
            AgentRuntimeService runtime,
            ToolConfirmationService confirmationService,
            AgentCancellationService cancellationService
    ) {
    }

    static class BlockingToolConfirmationRepository implements ToolConfirmationRepository {

        private final ToolConfirmationRepository delegate;
        private final AtomicReference<ToolConfirmation.Status> blockedStatus =
                new AtomicReference<>();
        private volatile CountDownLatch decisionBlocked = new CountDownLatch(0);
        private volatile CountDownLatch decisionRelease = new CountDownLatch(0);

        BlockingToolConfirmationRepository(ToolConfirmationRepository delegate) {
            this.delegate = delegate;
        }

        void blockNext(ToolConfirmation.Status status) {
            blockedStatus.set(status);
            decisionBlocked = new CountDownLatch(1);
            decisionRelease = new CountDownLatch(1);
        }

        void awaitBlocked() throws InterruptedException {
            assertThat(decisionBlocked.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void releaseBlockedDecision() {
            decisionRelease.countDown();
        }

        @Override
        public ToolConfirmation save(ToolConfirmation confirmation) {
            return delegate.save(confirmation);
        }

        @Override
        public List<ToolConfirmation> findPendingByOwnerAndSessionId(
                String ownerKey,
                String sessionId
        ) {
            return delegate.findPendingByOwnerAndSessionId(ownerKey, sessionId);
        }

        @Override
        public ToolConfirmation findById(String confirmationId) {
            return delegate.findById(confirmationId);
        }

        @Override
        public List<ToolConfirmation> findByStatus(ToolConfirmation.Status status, int limit) {
            return delegate.findByStatus(status, limit);
        }

        @Override
        public ToolConfirmation decidePending(
                String confirmationId,
                ToolConfirmation.Status status,
                String decisionReason,
                Instant decidedAt
        ) {
            if (blockedStatus.compareAndSet(status, null)) {
                decisionBlocked.countDown();
                try {
                    if (!decisionRelease.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release decision");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("decision wait interrupted", exception);
                }
            }
            return delegate.decidePending(
                    confirmationId, status, decisionReason, decidedAt);
        }

        @Override
        public int expirePendingBefore(Instant cutoff) {
            return delegate.expirePendingBefore(cutoff);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class RaceConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:approval_cancel_race_" + UUID.randomUUID()
                            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
                            + ";LOCK_TIMEOUT=5000",
                    "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        NamedParameterJdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AgentRuntimeService runtimeService(
                NamedParameterJdbcTemplate jdbc,
                PlatformTransactionManager transactionManager,
                ObjectMapper objectMapper,
                Clock clock
        ) {
            return new AgentRuntimeService(
                    new JdbcAgentRunRepository(
                            jdbc, new TransactionTemplate(transactionManager)),
                    new AgentRuntimeProperties(), objectMapper, clock);
        }

        @Bean
        BlockingToolConfirmationRepository confirmationRepository(
                NamedParameterJdbcTemplate jdbc,
                ObjectMapper objectMapper
        ) {
            return new BlockingToolConfirmationRepository(
                    new JdbcToolConfirmationRepository(jdbc, objectMapper));
        }

        @Bean
        ToolConfirmationService confirmationService(
                BlockingToolConfirmationRepository repository,
                Clock clock
        ) {
            return new ToolConfirmationService(
                    repository, new AgentTraceSanitizer(160),
                    Duration.ofMinutes(15), clock);
        }

        @Bean
        AgentCancellationService cancellationService(
                AgentRuntimeService runtimeService,
                ToolConfirmationService confirmationService
        ) {
            AgentTraceSanitizer sanitizer = new AgentTraceSanitizer(160);
            AgentTraceRecorder recorder = new AgentTraceRecorder(
                    new InMemoryAgentTraceRepository(), sanitizer);
            return new AgentCancellationService(
                    runtimeService, recorder, sanitizer, confirmationService);
        }
    }
}
