package org.ocean.admin.gis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

/**
 * GIS任务执行配置
 * 正常情况下只并行 2 个任务；队列塞满 100 个后才扩到 4
 */
@EnableAsync
@EnableScheduling
@Configuration
public class GisTaskExecutionConfig {

    @Bean("gisTaskExecutor")
    public Executor gisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("geo-task-");
        executor.initialize();
        return executor;
    }
}
