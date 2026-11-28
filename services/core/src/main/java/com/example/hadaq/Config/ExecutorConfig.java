package com.example.hadaq.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// background work: simulation jobs, forwarding events to the agents, the timed demo run
@Configuration
public class ExecutorConfig {

    public static final String WORKER = "hadaqWorker";
    public static final String SCHEDULER = "hadaqScheduler";

    @Bean(name = WORKER)
    public ThreadPoolTaskExecutor hadaqWorker() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("hadaq-worker-");
        executor.initialize();
        return executor;
    }

    @Bean(name = SCHEDULER)
    public ThreadPoolTaskScheduler hadaqScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);  // the demo run, sensor feed, camera, vision
        scheduler.setThreadNamePrefix("hadaq-demo-");
        scheduler.initialize();
        return scheduler;
    }
}
