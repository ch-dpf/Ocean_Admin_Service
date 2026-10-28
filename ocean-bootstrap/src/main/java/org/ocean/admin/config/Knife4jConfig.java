package org.ocean.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.ocean.admin.web.realtime.RealtimeEventController;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 文档配置。
 */
@Configuration(proxyBeanMethods = false)
public class Knife4jConfig {

    /**
     * 文档基础信息。
     */
    @Bean
    public OpenAPI oceanAdminOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ocean Admin Service API")
                        .description("Ocean Admin Service 接口文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("DeepSea"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }

    /**
     * 平台接口文档分组
     * 按平台路径收录平台控制器与bootstrap中的平台SSE入口。
     */
    @Bean
    public GroupedOpenApi systemApiGroup() {
        return GroupedOpenApi.builder()
                .group("平台接口")
                .packagesToScan("org.ocean.admin.platform", "org.ocean.admin.web.realtime")
                .pathsToMatch(
                        "/api/auth/**",
                        "/api/member/**",
                        "/api/log/**",
                        RealtimeEventController.PLATFORM_STREAM_PATH
                )
                .build();
    }


    /**
     * Gis地理信息接口文档分组
     * 按GIS路径收录GIS控制器与bootstrap中的GIS SSE入口。
     */
    @Bean
    public GroupedOpenApi gisApiGroup() {
        return GroupedOpenApi.builder()
                .group("Geo地理信息接口")
                .packagesToScan("org.ocean.admin.gis", "org.ocean.admin.web.realtime")
                .pathsToMatch("/api/gis/**")
                .build();
    }

}