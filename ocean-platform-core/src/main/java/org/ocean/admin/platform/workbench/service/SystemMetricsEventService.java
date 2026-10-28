package org.ocean.admin.platform.workbench.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.realtime.EventMessage;
import org.ocean.admin.kernel.realtime.RealtimeEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 仅在有订阅者时采集系统指标，连接与传输统一交给公共事件通道。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SystemMetricsEventService {
    private static final String EVENT_TYPE = "platform.metrics.updated";
    private final MonitorService monitorService;
    private final RealtimeEventPublisher publisher;
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "system-metrics-events");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::publishSnapshot, 0, 2, TimeUnit.SECONDS);
    }

    public void publishSnapshot() {
        if (!publisher.hasSubscribers(EVENT_TYPE)) return;
        try {
            publisher.publish(EventMessage.create(EVENT_TYPE, "ocean.platform", "system/metrics",
                    null, monitorService.getRealtimeMetrics()));
        } catch (RuntimeException ex) {
            log.warn("系统指标事件发布失败", ex);
        }
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
