package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.console.JdbcToolConfirmationRepository;
import com.hkdzagent.agent.console.ToolConfirmation;
import com.hkdzagent.agent.security.ActorIdentity;
import com.hkdzagent.agent.tool.JdbcToolExecutionJournalRepository;
import com.hkdzagent.agent.tool.ToolExecutionJournalEntry;
import com.hkdzagent.agent.tool.ToolInvocationContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres-integration")
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "POSTGRES_RECOVERY_PHASE", matches = "(?i)(seed|verify)")
class RealPostgresContainerRestartRecoveryTest {

    private static final String SCHEMA = "runtime_restart_it";
    private static final String RUN_ID = "00000000-0000-0000-0000-000000000041";
    private static final String APPROVAL_ID = "00000000-0000-0000-0000-000000000042";
    private static final String TOOL_EXECUTION_TOKEN = "00000000-0000-0000-0000-000000000043";

    @Test
    void provesRuntimeStateSurvivesDatabaseProcessRestart() {
        String phase = environment("POSTGRES_RECOVERY_PHASE", "").toLowerCase();
        Database database = database();

        if (phase.equals("seed")) {
            seedBeforeRestart(database);
            return;
        }
        verifyAfterRestartAndClean(database);
    }

    private void seedBeforeRestart(Database database) {
        database.adminJdbc().execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        database.adminJdbc().execute("CREATE SCHEMA " + SCHEMA);
        migrate(database.runtimeDataSource());

        JdbcTemplate jdbc = new JdbcTemplate(database.runtimeDataSource());
        jdbc.execute("""
                CREATE TABLE restart_probe (
                    id INTEGER PRIMARY KEY,
                    database_started_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO restart_probe (id, database_started_at)
                SELECT 1, pg_postmaster_start_time()
                """);

        Instant now = Instant.now();
        JdbcAgentRunRepository runs = runtimeRepository(database.runtimeDataSource());
        AgentRun created = runs.create(AgentRun.created(
                RUN_ID,
                ActorIdentity.user("postgres-restart-user"),
                "postgres-restart-session",
                "postgres-restart-conversation",
                "postgres-restart-trace",
                "resume a protected tool after database restart",
                5,
                now
        ), "{\"source\":\"postgres-container-restart\"}");
        AgentRunClaim claimed = runs.claim(
                created.runId(), "worker-before-db-restart", now, Duration.ofMinutes(1));

        ToolConfirmation pending = approvalRepository(database.runtimeDataSource()).save(new ToolConfirmation(
                APPROVAL_ID,
                created.ownerKey(),
                created.sessionId(),
                created.traceId(),
                created.runId(),
                "commandExecuteTool",
                "1.0.0",
                "restart-call",
                "b".repeat(64),
                "{\"command\":\"mvn test\"}",
                ToolConfirmation.Status.PENDING,
                null,
                now,
                now.plus(Duration.ofMinutes(30)),
                null
        ));
        AgentRun waiting = runs.update(
                claimed.run().waitForApproval(
                        pending.id(),
                        "{\"schemaVersion\":1,\"toolCall\":{\"id\":\"restart-call\"}}",
                        "worker-before-db-restart",
                        claimed.run().leaseEpoch(),
                        now.plusSeconds(1)
                ),
                claimed.run().version(),
                "worker-before-db-restart"
        );
        runs.appendEvent(
                RUN_ID,
                AgentRunEventType.APPROVAL_REQUIRED,
                "{\"approvalId\":\"" + APPROVAL_ID + "\"}",
                now.plusSeconds(1)
        );
        JdbcToolExecutionJournalRepository toolJournal =
                toolJournalRepository(database.runtimeDataSource());
        ToolExecutionJournalEntry uncertainExecution = ToolExecutionJournalEntry.started(
                new ToolInvocationContext(
                        created.ownerKey(), created.runId(), created.traceId(),
                        "restart-tool-call", Set.of()),
                "commandExecuteTool", "1.0.0", "c".repeat(64),
                TOOL_EXECUTION_TOKEN, now.plusSeconds(2));
        assertThat(toolJournal.reserve(uncertainExecution).acquired()).isTrue();

        assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(waiting.leaseOwner()).isNull();
        assertThat(pending.status()).isEqualTo(ToolConfirmation.Status.PENDING);
    }

    private void verifyAfterRestartAndClean(Database database) {
        assertThat(database.adminJdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)",
                Boolean.class,
                SCHEMA
        )).as("seed phase must run before verify phase").isTrue();

        try {
            JdbcTemplate jdbc = new JdbcTemplate(database.runtimeDataSource());
            assertThat(jdbc.queryForObject("""
                    SELECT pg_postmaster_start_time() > database_started_at
                    FROM restart_probe
                    WHERE id = 1
                    """, Boolean.class))
                    .as("PostgreSQL server process must have restarted after the seed phase")
                    .isTrue();

            JdbcAgentRunRepository runs = runtimeRepository(database.runtimeDataSource());
            JdbcToolConfirmationRepository approvals = approvalRepository(database.runtimeDataSource());
            AgentRun waiting = runs.findById(RUN_ID);
            ToolConfirmation pending = approvals.findById(APPROVAL_ID);

            assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            assertThat(waiting.pendingApprovalId()).isEqualTo(APPROVAL_ID);
            assertThat(waiting.checkpointJson()).contains("restart-call");
            assertThat(waiting.leaseOwner()).isNull();
            assertThat(pending.status()).isEqualTo(ToolConfirmation.Status.PENDING);
            assertThat(pending.toolVersion()).isEqualTo("1.0.0");
            assertThat(pending.toolCallId()).isEqualTo("restart-call");
            assertThat(pending.argumentsHash()).isEqualTo("b".repeat(64));
            Instant decidedAt = Instant.now();
            JdbcToolExecutionJournalRepository toolJournal =
                    toolJournalRepository(database.runtimeDataSource());
            assertThat(toolJournal.summarize(RUN_ID).startedExecutions()).isOne();
            ToolExecutionJournalEntry durableUncertain = toolJournal.find(
                    RUN_ID, "restart-tool-call");
            assertThat(durableUncertain.executionToken()).isEqualTo(TOOL_EXECUTION_TOKEN);
            assertThat(toolJournal.reserve(ToolExecutionJournalEntry.started(
                    new ToolInvocationContext(
                            waiting.ownerKey(), waiting.runId(), waiting.traceId(),
                            "restart-tool-call", Set.of()),
                    "commandExecuteTool", "1.0.0", "c".repeat(64),
                    "00000000-0000-0000-0000-000000000044", decidedAt)).acquired())
                    .isFalse();

            assertThat(approvals.decidePending(
                    APPROVAL_ID, ToolConfirmation.Status.APPROVED, "approved after restart", decidedAt
            )).isNotNull();
            assertThat(approvals.decidePending(
                    APPROVAL_ID, ToolConfirmation.Status.APPROVED, "duplicate approval", decidedAt
            )).isNull();

            AgentRun resumedCandidate = waiting.resumeApproval(
                    APPROVAL_ID,
                    "worker-after-db-restart",
                    decidedAt,
                    decidedAt.plusSeconds(30)
            );
            AgentRun resumed = runs.update(resumedCandidate, waiting.version(), null);
            assertThat(resumed.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(resumed.pendingApprovalId()).isNull();
            assertThat(runs.update(resumedCandidate, waiting.version(), null)).isNull();
            assertThat(runs.findEventsAfter(RUN_ID, 0, 20))
                    .extracting(AgentRunEvent::sequence)
                    .containsExactly(1L, 2L);
            assertThat(jdbc.queryForObject(
                    "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success",
                    Integer.class
            )).isGreaterThanOrEqualTo(15);
        } finally {
            database.adminJdbc().execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private void migrate(DriverManagerDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .locations("classpath:db/migration/postgresql")
                .load()
                .migrate();
    }

    private JdbcAgentRunRepository runtimeRepository(DriverManagerDataSource dataSource) {
        return new JdbcAgentRunRepository(
                new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    private JdbcToolConfirmationRepository approvalRepository(DriverManagerDataSource dataSource) {
        return new JdbcToolConfirmationRepository(
                new NamedParameterJdbcTemplate(dataSource), new ObjectMapper());
    }

    private JdbcToolExecutionJournalRepository toolJournalRepository(
            DriverManagerDataSource dataSource
    ) {
        return new JdbcToolExecutionJournalRepository(
                new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private Database database() {
        String host = environment("POSTGRES_HOST", "localhost");
        String port = environment("POSTGRES_PORT", "5432");
        String name = environment("POSTGRES_DB", "xingclaw_agent");
        String username = environment("POSTGRES_USER", "xingclaw_agent");
        String password = environment("POSTGRES_PASSWORD", "xingclaw-local-password");
        String baseUrl = "jdbc:postgresql://" + host + ":" + port + "/" + name;

        DriverManagerDataSource adminDataSource = new DriverManagerDataSource(baseUrl, username, password);
        DriverManagerDataSource runtimeDataSource = new DriverManagerDataSource(
                baseUrl + "?currentSchema=" + SCHEMA, username, password);
        return new Database(new JdbcTemplate(adminDataSource), runtimeDataSource);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record Database(JdbcTemplate adminJdbc, DriverManagerDataSource runtimeDataSource) {
    }
}
