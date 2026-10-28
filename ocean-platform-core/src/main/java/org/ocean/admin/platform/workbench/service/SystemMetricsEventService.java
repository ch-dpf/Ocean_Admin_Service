package org.ocean.admin.platform.workbench.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.realtime.EventMessage;
import org.ocean.admin.kernel.realtime.RealtimeEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 系统指标事件服务
 */
@Component
@Slf4j
public class SystemMetricsEventService {
    private static final String EVENT_TYPE = "platform.metrics.updated";
    private final MonitorService monitorService;
    private final RealtimeEventPublisher publisher;
    private ScheduledExecutorService scheduler;

    public SystemMetricsEventService(MonitorService monitorService,
                                    @Qualifier("systemMetricsSsePublisher") RealtimeEventPublisher publisher) {
        this.monitorService = monitorService;
        this.publisher = publisher;
    }

    /**
     * 初始化
     * 定时发布实时系统指标快照
     * 服务启动后0秒开始执行，每2秒执行一次
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "system-metrics-events");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::publishSnapshot, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * 发布系统指标快照
     */
    public void publishSnapshot() {
        if (!publisher.hasSubscribers(EVENT_TYPE)) return;
        try {
            publisher.publish(EventMessage.create(EVENT_TYPE, "ocean.platform", "system/metrics",
                    null, monitorService.getRealtimeMetrics()));
        } catch (RuntimeException ex) {
            log.warn("系统指标事件发布失败", ex);
        }
    }

    /**
     * 销毁
     */
    @PreDestroy
    public void destroy() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
