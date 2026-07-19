package com.hkdzagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hkdzagent.agent.ai.LLMClient;
import com.hkdzagent.agent.trace.AgentTraceRecorder;
import com.hkdzagent.agent.trace.AgentTraceSanitizer;
import com.hkdzagent.agent.console.ToolConfirmationProperties;
import com.hkdzagent.agent.console.ToolConfirmationService;
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
            ToolConfirmationProperties confirmationProperties
    ) {
        return new AgentRuntimeExecutor(
                runtimeService, llmClient, traceRecorder, sanitizer,
                approvalPauseService, confirmationProperties);
    }

    @Bean
    public AgentApprovalPauseService agentApprovalPauseService(
            ToolConfirmationService confirmationService,
            AgentRuntimeService runtimeService,
            AgentTraceSanitizer sanitizer
    ) {
        return new AgentApprovalPauseService(confirmationService, runtimeService, sanitizer);
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
            @Qualifier("agentRuntimeTaskExecutor") Executor executor
    ) {
        return new AgentApprovalOrchestrator(
                confirmationService, confirmationRepository, runtimeService,
                runtimeExecutor, traceRecorder, executor);
    }

    @Bean
    public AgentApprovalRecoveryScheduler agentApprovalRecoveryScheduler(
            AgentApprovalOrchestrator orchestrator
    ) {
        return new AgentApprovalRecoveryScheduler(orchestrator);
    }
}
