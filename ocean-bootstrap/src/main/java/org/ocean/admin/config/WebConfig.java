package org.ocean.admin.config;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ocean.admin.web.realtime.RealtimeSsePublisher;
import org.ocean.admin.web.realtime.RealtimeEventController;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.ocean.admin.interceptor.RolePermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web配置类
 *
 * @author DeepSea
 * @since 2026-04-15
 */
@Configuration
@Slf4j
public class WebConfig implements WebMvcConfigurer {

    private final RolePermissionInterceptor rolePermissionInterceptor;
    private final RealtimeSsePublisher realtimeSsePublisher;

    public WebConfig(RolePermissionInterceptor rolePermissionInterceptor, RealtimeSsePublisher realtimeSsePublisher) {
        this.rolePermissionInterceptor = rolePermissionInterceptor;
        this.realtimeSsePublisher = realtimeSsePublisher;
    }

    /**
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rolePermissionInterceptor)
                .addPathPatterns("/api/**")
                // SSE沿用原入口的公开访问方式，鉴权后续统一接入。
                .excludePathPatterns(RealtimeEventController.PLATFORM_STREAM_PATH,
                        RealtimeEventController.GIS_STREAM_PATH);
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override
            public void afterConcurrentHandlingStarted(HttpServletRequest request,
                    HttpServletResponse response, Object handler) {
                Object emitter = request.getAttribute(RealtimeSsePublisher.REQUEST_EMITTER_ATTRIBUTE);
                if (emitter instanceof SseEmitter sseEmitter) realtimeSsePublisher.activate(sseEmitter);
            }
        }).addPathPatterns(RealtimeEventController.PLATFORM_STREAM_PATH,
                RealtimeEventController.GIS_STREAM_PATH);
    }
}
