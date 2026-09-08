package org.ocean.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Ocean Admin 服务的 Spring Boot 启动入口。 */
@SpringBootApplication
public class OceanAdminApplication {
    /** 启动应用上下文及内嵌 Web 服务。 */
    public static void main(String[] args) {
        SpringApplication.run(OceanAdminApplication.class, args);
    }
}
