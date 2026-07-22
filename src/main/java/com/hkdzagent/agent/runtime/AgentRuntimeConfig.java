package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.console.ToolConfirmationService;
import com.hkdzagent.agent.tool.ToolExecutionPipeline;
import com.hkdzagent.agent.im.FeishuResultOutboxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration
@EnableScheduling
public class AgentRuntimeConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agent.runtime", name = "repository", havingValue = "jdbc")
    public JdbcAgentRunRepository jdbcAgentRunRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcAgentRunRepository(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean(AgentRunRepository.class)
    public InMemoryAgentRunRepository inMemoryAgentRunRepository() {
        return new InMemoryAgentRunRepository();
    }

    @Bean
    public AgentRuntimeService agentRuntimeService(
            AgentRunRepository repository,
            AgentRuntimeProperties properties,
            ObjectMapper objectMapper
    ) {
        return new AgentRuntimeService(repository, properties, objectMapper, Clock.systemUTC());
    }

    @Bean
    public AgentRuntimeExecutor agentRuntimeExecutor(
            AgentRuntimeService runtimeService,
            LLMClient llmClient,
            AgentTraceRecorder traceRecorder,
            AgentTraceSanitizer sanitizer,
            AgentApprovalPauseService approvalPauseService,
            ToolConfirmationProperties confirmationProperties,
            ToolExecutionPipeline toolExecutionPipeline,
            ApprovedToolExecutionService approvedToolExecutionService,
            AgentCompletionService completionService,
            AgentFailureService failureService,
            AgentRunLeaseHeartbeatFactory heartbeatFactory
    ) {
        return new AgentRuntimeExecutor(
                runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties, toolExecutionPipeline,
                approvedToolExecutionService, completionService, failureService, heartbeatFactory);
    }

    @Bean(name = "agentRuntimeHeartbeatScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler agentRuntimeHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("agent-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public AgentRunLeaseHeartbeatFactory agentRunLeaseHeartbeatFactory(
            AgentRuntimeService runtimeService,
            @Qualifier("agentRuntimeHeartbeatScheduler") ThreadPoolTaskScheduler scheduler,
            AgentRuntimeProperties properties
    ) {
        return new AgentRunLeaseHeartbeatFactory(
                runtimeService, scheduler, properties.getHeartbeatInterval());
    }

    @Bean
    public ApprovedToolExecutionService approvedToolExecutionService(
            ToolConfirmationService confirmationService,
            ToolExecutionPipeline toolExecutionPipeline,
            ObjectMapper objectMapper
    ) {
        return new ApprovedToolExecutionService(
                confirmationService, toolExecutionPipeline, objectMapper);
    }

    @Bean
    public AgentApprovalPauseService agentApprovalPauseService(
            ToolConfirmationService confirmationService,
            AgentRuntimeService runtimeService,
            AgentTraceSanitizer sanitizer,
            ObjectMapper objectMapper,
            FeishuResultOutboxService outboxService
    ) {
        return new AgentApprovalPauseService(
                confirmationService, runtimeService, sanitizer, objectMapper, outboxService);
    }

    @Bean(name = "agentRuntimeTaskExecutor")
    public Executor agentRuntimeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-runtime-");
        executor.initialize();
        return executor;
    }

    @Bean
    public AgentApprovalOrchestrator agentApprovalOrchestrator(
            ToolConfirmationService confirmationService,
            com.hkdzagent.agent.console.ToolConfirmationRepository confirmationRepository,
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentTraceRecorder traceRecorder,
            AgentFailureService failureService,
            @Qualifier("agentRuntimeTaskExecutor") Executor executor
    ) {
        return new AgentApprovalOrchestrator(
                confirmationService, confirmationRepository, runtimeService,
                runtimeExecutor, traceRecorder, failureService, executor);
    }

    @Bean
    public AgentApprovalRecoveryScheduler agentApprovalRecoveryScheduler(
            AgentApprovalOrchestrator orchestrator
    ) {
        return new AgentApprovalRecoveryScheduler(orchestrator);
    }

    @Bean
    public AgentRunRecoveryClassifier agentRunRecoveryClassifier() {
        return new AgentRunRecoveryClassifier();
    }

    @Bean
    public AgentRunRecoveryService agentRunRecoveryService(
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentFailureService failureService,
            AgentRunRecoveryClassifier classifier,
            AgentRuntimeProperties properties,
            @Qualifier("agentRuntimeTaskExecutor") Executor executor
    ) {
        return new AgentRunRecoveryService(
                runtimeService, runtimeExecutor, failureService,
                classifier, properties, executor);
    }

    @Bean
    public AgentRunRecoveryScheduler agentRunRecoveryScheduler(
            AgentRunRecoveryService recoveryService
    ) {
        return new AgentRunRecoveryScheduler(recoveryService);
    }

    @Bean
    public AgentRunCoordinator agentRunCoordinator(
            AgentRuntimeService runtimeService,
            AgentRuntimeExecutor runtimeExecutor,
            AgentTraceRecorder traceRecorder
    ) {
        return new AgentRunCoordinator(runtimeService, runtimeExecutor, traceRecorder);
    }

    @Bean
    public AgentCompletionService agentCompletionService(
            AgentRuntimeService runtimeService,
            FeishuResultOutboxService outboxService
    ) {
        return new AgentCompletionService(runtimeService, outboxService);
    }

    @Bean
    public AgentFailureService agentFailureService(
            AgentRuntimeService runtimeService,
            FeishuResultOutboxService outboxService
    ) {
        return new AgentFailureService(runtimeService, outboxService);
    }
}
