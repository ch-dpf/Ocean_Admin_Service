package org.ocean.admin.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ocean.admin.gis.realtime.controller.GisRealtimeEventController;
import org.ocean.admin.gis.realtime.service.GisRealtimeSsePublisher;
import org.ocean.admin.platform.workbench.controller.MonitorController;
import org.ocean.admin.platform.workbench.service.SystemMetricsSsePublisher;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.ocean.admin.interceptor.RolePermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Web配置类
 *
 * @author DeepSea
 * @since 2026-04-15
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RolePermissionInterceptor rolePermissionInterceptor;
    private final SystemMetricsSsePublisher systemMetricsSsePublisher;
    private final GisRealtimeSsePublisher gisRealtimeSsePublisher;

    public WebConfig(RolePermissionInterceptor rolePermissionInterceptor,
                     SystemMetricsSsePublisher systemMetricsSsePublisher,
                     GisRealtimeSsePublisher gisRealtimeSsePublisher) {
        this.rolePermissionInterceptor = rolePermissionInterceptor;
        this.systemMetricsSsePublisher = systemMetricsSsePublisher;
        this.gisRealtimeSsePublisher = gisRealtimeSsePublisher;
    }

    /** 数据集类型示意图随 GIS 模块打包，同一类型的卡片复用浏览器缓存。 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/gis/dataSet/thumbnails/**")
                .addResourceLocations("classpath:/static/dataset-thumbnails/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }

    /**
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rolePermissionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(MonitorController.METRICS_STREAM_PATH,
                        GisRealtimeEventController.STREAM_PATH);
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override
            public void afterConcurrentHandlingStarted(HttpServletRequest request,
                    HttpServletResponse response, Object handler) {
                Object metricsEmitter = request.getAttribute(SystemMetricsSsePublisher.REQUEST_EMITTER_ATTRIBUTE);
                if (metricsEmitter instanceof SseEmitter sseEmitter) systemMetricsSsePublisher.activate(sseEmitter);
                Object gisEmitter = request.getAttribute(GisRealtimeSsePublisher.REQUEST_EMITTER_ATTRIBUTE);
                if (gisEmitter instanceof SseEmitter sseEmitter) gisRealtimeSsePublisher.activate(sseEmitter);
            }
        }).addPathPatterns(MonitorController.METRICS_STREAM_PATH,
                GisRealtimeEventController.STREAM_PATH);
    }
}
