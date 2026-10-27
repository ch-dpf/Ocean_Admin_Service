package org.ocean.admin.gis.config;

import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.terrain.engine.mago.MagoTerrainProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * 基于JVM可见的CPU和内存资源配额
 * 服务启动时确定GEO后台任务并发数
 */
@Slf4j
@EnableAsync
@EnableScheduling
@Configuration
public class TaskExecutionConfig {

    /** 二进制 GiB，内存预算单位。 */
    private static final long GIB = 1024L * 1024 * 1024;

    /** 启动时生成的线程池规格 */
    private final ExecutorSizing sizing;

    public TaskExecutionConfig(MagoTerrainProperties terrainProperties) {
        int processors = Runtime.getRuntime().availableProcessors();
        long totalMemory = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class)
                .getTotalMemorySize();
        long maxHeap = Runtime.getRuntime().maxMemory();
        this.sizing = calculateSizing(processors, totalMemory, maxHeap,
                terrainProperties.isEnabled(), terrainProperties.getMaxHeap());

        log.info("GEO 信息线程池自动配置：可用处理器数={}，环境总内存/GB={}，"
                        + "JVM最大堆内存/GB={}，元数据线程数={}，通用任务线程数={}，归档线程数={}",
                processors, formatGigabytes(totalMemory), formatGigabytes(maxHeap), sizing.metadataThreads(),
                sizing.taskThreads(), sizing.archiveThreads());
        if (totalMemory <= 0) {
            log.warn("未能读取运行环境总内存，归档和已启用的地形任务并发数限制为 1");
        }
        if (terrainProperties.isEnabled()
                && sizing.terrainAvailableBytes() < sizing.terrainRequiredBytes()) {
            log.warn("地形子进程内存预算不足，通用任务池并发数限制为 1；"
                            + "单任务仍可能超出环境内存，请检查 mago max-heap 与主 JVM 堆配置: "
                            + "可用内存（字节）={}，所需内存（字节）={}",
                    sizing.terrainAvailableBytes(), sizing.terrainRequiredBytes());
        }
    }

    /** 将字节数格式化为 GB，保留两位小数。 */
    private static String formatGigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / 1_000_000_000.0);
    }

    /** GEO 元数据解析任务使用的执行器，采用无缓冲队列限制任务并发。 */
    @Bean("metadataExecutor")
    public ThreadPoolTaskExecutor metadataExecutor() {
        return createExecutor("geo-metadata-", sizing.metadataThreads(), 0);
    }

    /** GEO 通用后台任务使用的执行器，通过有界队列控制待执行任务数量。 */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        return createExecutor("geo-task-", sizing.taskThreads(), 100);
    }

    /** GEO 归档任务使用的执行器，以独立小线程池避免磁盘密集操作阻塞切片处理。 */
    @Bean("archiveExecutor")
    public ThreadPoolTaskExecutor archiveExecutor() {
        return createExecutor("geo-archive-", sizing.archiveThreads(), 20);
    }

    /** 创建线程池执行器 */
    private ThreadPoolTaskExecutor createExecutor(String threadNamePrefix, int threads, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        return executor;
    }

    /**
     * 根据 JVM 资源预算估算 GEO 任务线程池规格。
     */
    static ExecutorSizing calculateSizing(int processors, long totalMemory, long maxHeap,
            boolean terrainEnabled, String terrainMaxHeap) {
        int availableProcessors = Math.max(1, processors);
        long environmentMemory = Math.max(0, totalMemory);
        long jvmHeap = Math.max(0, maxHeap);

        int metadataThreads = boundedThreads(
                Math.min(availableProcessors / 4, jvmHeap / (2 * GIB)), 4);
        int taskThreads = boundedThreads(
                Math.min(availableProcessors / 4, jvmHeap / (4 * GIB)), 4);
        int archiveThreads = boundedThreads(
                Math.min(availableProcessors / 8, environmentMemory / (16 * GIB)), 2);

        long terrainAvailable = 0;
        long terrainRequired = 0;
        if (terrainEnabled) {
            long terrainHeap = parseTerrainHeap(terrainMaxHeap);
            // 为主应用堆外内存、其他服务与文件缓存预留至少 2GiB，并为子进程增加 25% 堆外预算。
            long reservedMemory = Math.max(2 * GIB, environmentMemory / 8);
            terrainAvailable = Math.max(0, Math.max(0, environmentMemory - jvmHeap) - reservedMemory);
            terrainRequired = Math.addExact(terrainHeap, terrainHeap / 4);
            taskThreads = Math.min(taskThreads, boundedThreads(terrainAvailable / terrainRequired, 4));
        }
        return new ExecutorSizing(metadataThreads, taskThreads, archiveThreads,
                terrainAvailable, terrainRequired);
    }

    /** 限制线程数在 1 到最大值之间。 */
    private static int boundedThreads(long candidate, int maximum) {
        return (int) Math.max(1, Math.min(candidate, maximum));
    }

    /** JVM 堆格式，只接受正整数加 k、m 或 g。 */
    private static long parseTerrainHeap(String maxHeap) {
        if (maxHeap == null || !maxHeap.matches("(?i)^[1-9]\\d*[kmg]$")) {
            throw new IllegalArgumentException("mago max-heap 必须使用正整数加 k、m 或 g，例如 8g");
        }
        return DataSize.parse(maxHeap.toUpperCase(Locale.ROOT) + "B").toBytes();
    }

    /** 线程池规格 */
    record ExecutorSizing(int metadataThreads, int taskThreads, int archiveThreads,
            long terrainAvailableBytes, long terrainRequiredBytes) {
    }
}
