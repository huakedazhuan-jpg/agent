package com.hkdzagent.agent.im;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(FeishuProperties.class)
public class FeishuAsyncConfig {

    @Bean(name = "feishuTaskExecutor")
    public Executor feishuTaskExecutor(FeishuProperties properties) {
        FeishuProperties.Async async = properties.async();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.coreSize());
        executor.setMaxPoolSize(async.maxSize());
        executor.setQueueCapacity(async.queueCapacity());
        executor.setThreadNamePrefix("feishu-event-");
        executor.initialize();
        return executor;
    }
}
