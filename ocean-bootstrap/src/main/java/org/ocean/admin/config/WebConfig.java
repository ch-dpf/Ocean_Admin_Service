package org.ocean.admin.config;

import lombok.extern.slf4j.Slf4j;
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

    public WebConfig(RolePermissionInterceptor rolePermissionInterceptor
    ) {
        this.rolePermissionInterceptor = rolePermissionInterceptor;
    }


    /**
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rolePermissionInterceptor)
                .addPathPatterns("/api/**");
    }
}
