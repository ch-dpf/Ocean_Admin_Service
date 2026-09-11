package org.ocean.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件上传配置类
 *
 * @author DeepSea
 * @since 2026-04-15
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class FileUploadConfig {

    /**
     * 基础路径
     */
    private String basePath;

    /**
     * 允许的文件类型
     */
    private String allowedTypes;
}
