package org.ocean.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 首位管理员受控初始化开关；密码只允许通过专用环境变量读取。 */
@ConfigurationProperties("ocean.bootstrap.admin")
public record BootstrapAdminProperties(boolean enabled, String username) {
}
