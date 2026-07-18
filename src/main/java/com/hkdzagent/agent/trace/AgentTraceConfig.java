package com.hkdzagent.agent.trace;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentTraceConfig {

    @Bean
    public InMemoryAgentTraceRepository inMemoryAgentTraceRepository() {
        return new InMemoryAgentTraceRepository();
    }

    @Bean
    public AgentTraceSanitizer agentTraceSanitizer() {
        return new AgentTraceSanitizer(120);
    }

    @Bean
    public AgentTraceRecorder agentTraceRecorder(
            InMemoryAgentTraceRepository repository,
            AgentTraceSanitizer sanitizer
    ) {
        return new AgentTraceRecorder(repository, sanitizer);
    }
}
