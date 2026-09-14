package org.ocean.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** 操作日志异步写入配置。 */
@Configuration
public class OperationLogConfig {

    @Bean(name = "operationLogExecutor")
    public Executor operationLogExecutor(
            @Value("${ocean.audit.operation-log.core-pool-size:2}") int corePoolSize,
            @Value("${ocean.audit.operation-log.max-pool-size:4}") int maxPoolSize,
            @Value("${ocean.audit.operation-log.queue-capacity:500}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("operation-log-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
