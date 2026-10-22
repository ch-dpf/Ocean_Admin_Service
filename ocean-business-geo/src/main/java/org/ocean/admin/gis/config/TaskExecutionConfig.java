package org.ocean.admin.gis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

/**
 * 任务执行配置
 * 正常情况下只并行 2 个任务；队列塞满 100 个后才扩到 4
 */
@EnableAsync
@EnableScheduling
@Configuration
public class TaskExecutionConfig {

    @Bean("metadataExecutor")
    public Executor metadataExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("geo-metadata-");
        executor.initialize();
        return executor;
    }

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("geo-task-");
        executor.initialize();
        return executor;
    }

    /** 归档属于磁盘密集任务，使用独立小线程池避免阻塞切片处理。 */
    @Bean("archiveExecutor")
    public Executor archiveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("geo-archive-");
        executor.initialize();
        return executor;
    }
}
