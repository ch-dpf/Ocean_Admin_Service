package org.ocean.admin.platform.workbench.websocket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.workbench.dto.SystemMetricsDTO;
import org.ocean.admin.platform.workbench.service.MonitorService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 系统指标实时 WebSocket 处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMetricsWebSocketHandler extends TextWebSocketHandler {

    private static final int PUSH_INTERVAL_SECONDS = 2;
    private static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;
    private static final int SEND_QUEUE_CAPACITY = 256;

    private final MonitorService monitorService;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService sendExecutor;

    @PostConstruct
    public void init() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "system-metrics-ws-push");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        sendExecutor = new ThreadPoolExecutor(
                2,
                Math.clamp(Runtime.getRuntime().availableProcessors(), 2, 8),
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "system-metrics-ws-send");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        scheduler.scheduleWithFixedDelay(
                this::broadcastSnapshotSafely,
                0,
                PUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (sendExecutor != null) {
            sendExecutor.shutdownNow();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession concurrentSession = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MILLIS,
                SEND_BUFFER_LIMIT_BYTES
        );
        sessions.put(session.getId(), concurrentSession);
        log.info("系统监控 WebSocket 连接建立: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
        sendSnapshot(concurrentSession);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        log.info("系统监控 WebSocket 连接关闭: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
    }

    private void broadcastSnapshotSafely() {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            String payload = buildSnapshotMessage();
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions.values()) {
                if (!session.isOpen()) {
                    sessions.remove(session.getId(), session);
                    continue;
                }
                sendExecutor.execute(() -> sendMessage(session, message));
            }
        } catch (Exception e) {
            log.error("广播系统监控消息失败", e);
        }
    }

    private void sendSnapshot(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                TextMessage message = new TextMessage(buildSnapshotMessage());
                sendExecutor.execute(() -> sendMessage(session, message));
            }
        } catch (Exception e) {
            log.warn("首次发送系统监控消息失败: sessionId={}, error={}", session.getId(), e.getMessage());
        }
    }

    private void sendMessage(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            } else {
                sessions.remove(session.getId(), session);
            }
        } catch (Exception e) {
            sessions.remove(session.getId(), session);
            log.warn("发送系统监控消息失败: sessionId={}, error={}", session.getId(), e.getMessage());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception closeException) {
                log.debug("关闭异常系统监控连接失败: sessionId={}", session.getId(), closeException);
            }
        }
    }

    private String buildSnapshotMessage() throws Exception {
        SystemMetricsDTO metrics = monitorService.getRealtimeMetrics();
        return objectMapper.writeValueAsString(ResponseResult.success(metrics));
    }
}

