package org.ocean.admin.platform.workbench.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.concurrent.*;

/**
 * 系统指标实时 WebSocket 处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMetricsWebSocketHandler extends TextWebSocketHandler {

    private final MonitorService monitorService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "system-metrics-ws-push");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduler.scheduleAtFixedRate(this::broadcastSnapshotSafely, 0, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("系统监控 WebSocket 连接建立: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
        sendSnapshot(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("系统监控 WebSocket 连接关闭: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
    }

    private void broadcastSnapshotSafely() {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            String payload = buildSnapshotMessage();
            TextMessage message = new TextMessage(payload);
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }
                try {
                    session.sendMessage(message);
                } catch (Exception e) {
                    log.warn("发送系统监控消息失败: sessionId={}, error={}", session.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("广播系统监控消息失败", e);
        }
    }

    private void sendSnapshot(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(buildSnapshotMessage()));
            }
        } catch (Exception e) {
            log.warn("首次发送系统监控消息失败: sessionId={}, error={}", session.getId(), e.getMessage());
        }
    }

    private String buildSnapshotMessage() throws Exception {
        SystemMetricsDTO metrics = monitorService.getRealtimeMetrics();
        return objectMapper.writeValueAsString(ResponseResult.success(metrics));
    }
}

