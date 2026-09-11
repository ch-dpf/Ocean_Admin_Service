package org.ocean.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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
     * 扫描 org.ocean.admin.platform
     */
    @Bean
    public GroupedOpenApi systemApiGroup() {
        return GroupedOpenApi.builder()
                .group("system")
                .packagesToScan("org.ocean.admin.platform")
                .pathsToMatch(
                        "/api/auth/**",
                        "/api/member/**",
                        "/api/log/**"
                )
                .build();
    }


    @Bean
    public GroupedOpenApi gisApiGroup() {
        return GroupedOpenApi.builder()
                .group("gis")
                .packagesToScan("org.ocean.admin.gis")
                .pathsToMatch("/api/gis/**")
                .build();
    }

}