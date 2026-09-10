package org.ocean.admin.config;


import org.ocean.admin.platform.workbench.websocket.SystemMetricsWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SystemMetricsWebSocketHandler systemMetricsWebSocketHandler;

    public WebSocketConfig(SystemMetricsWebSocketHandler systemMetricsWebSocketHandler) {
        this.systemMetricsWebSocketHandler = systemMetricsWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册系统监控实时指标 WebSocket 端点
        registry.addHandler(systemMetricsWebSocketHandler, "/ws/system-metrics")
                .setAllowedOriginPatterns("*");
    }
}
