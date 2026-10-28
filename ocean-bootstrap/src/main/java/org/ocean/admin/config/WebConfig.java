package org.ocean.admin.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ocean.admin.gis.realtime.controller.GisRealtimeEventController;
import org.ocean.admin.gis.realtime.service.GisRealtimeSsePublisher;
import org.ocean.admin.platform.realtime.controller.PlatformRealtimeEventController;
import org.ocean.admin.platform.realtime.service.PlatformRealtimeSsePublisher;
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
public class WebConfig implements WebMvcConfigurer {

    private final RolePermissionInterceptor rolePermissionInterceptor;
    private final PlatformRealtimeSsePublisher platformRealtimeSsePublisher;
    private final GisRealtimeSsePublisher gisRealtimeSsePublisher;

    public WebConfig(RolePermissionInterceptor rolePermissionInterceptor,
                     PlatformRealtimeSsePublisher platformRealtimeSsePublisher,
                     GisRealtimeSsePublisher gisRealtimeSsePublisher) {
        this.rolePermissionInterceptor = rolePermissionInterceptor;
        this.platformRealtimeSsePublisher = platformRealtimeSsePublisher;
        this.gisRealtimeSsePublisher = gisRealtimeSsePublisher;
    }

    /**
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rolePermissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(PlatformRealtimeEventController.STREAM_PATH,
                        GisRealtimeEventController.STREAM_PATH);
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override
            public void afterConcurrentHandlingStarted(HttpServletRequest request,
                    HttpServletResponse response, Object handler) {
                Object platformEmitter = request.getAttribute(PlatformRealtimeSsePublisher.REQUEST_EMITTER_ATTRIBUTE);
                if (platformEmitter instanceof SseEmitter sseEmitter) platformRealtimeSsePublisher.activate(sseEmitter);
                Object gisEmitter = request.getAttribute(GisRealtimeSsePublisher.REQUEST_EMITTER_ATTRIBUTE);
                if (gisEmitter instanceof SseEmitter sseEmitter) gisRealtimeSsePublisher.activate(sseEmitter);
            }
        }).addPathPatterns(PlatformRealtimeEventController.STREAM_PATH,
                GisRealtimeEventController.STREAM_PATH);
    }
}
