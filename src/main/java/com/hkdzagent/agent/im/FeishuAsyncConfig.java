package com.hkdzagent.agent.im;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class FeishuAsyncConfig {

    @Bean(name = "feishuTaskExecutor")
    public Executor feishuTaskExecutor(
            @Value("${feishu.async.core-size:2}") int coreSize,
            @Value("${feishu.async.max-size:4}") int maxSize,
            @Value("${feishu.async.queue-capacity:100}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("feishu-event-");
        executor.initialize();
        return executor;
    }
}
