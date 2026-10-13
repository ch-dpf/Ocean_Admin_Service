package org.ocean.admin.gis.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.ocean.admin.gis.dto.TaskProgressMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.ObjectMapper;

/**
 * 任务实时 处理器
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    // 按会话串行化并发发送，避免多任务同时推送损坏 WebSocket 会话。
    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES));
        log.info("任务 WebSocket 连接建立: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        log.info("任务 WebSocket 连接关闭: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
    }

    /**
     * 广播任务进度给所有连接的客户端
     */
    public void broadcastTaskProgress(TaskProgressMessage message) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(jsonMessage);

            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (Exception e) {
                        log.error("发送消息失败: sessionId={}", session.getId(), e);
                        sessions.remove(session.getId());
                        closeQuietly(session);
                    }
                }
            }
        } catch (Exception e) {
            log.error("序列化消息失败", e);
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 会话已不可用，清理失败无需影响任务线程。
        }
    }
}
