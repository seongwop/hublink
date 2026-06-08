package com.msa.ai_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AiConsumerSchedulerConfig {
    @Value("${ai.stream.consumer.deadline-requested.concurrency:1}")
    private int concurrency;

    @Bean
    public TaskScheduler aiConsumerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(concurrency);
        scheduler.setThreadNamePrefix("ai-consumer-");
        scheduler.initialize();
        return scheduler;
    }
}