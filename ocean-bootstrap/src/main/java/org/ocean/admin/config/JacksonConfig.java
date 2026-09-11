package org.ocean.admin.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Jackson 配置 - 解决 Long 类型在前端精度丢失问题。
 * <p>
 * 雪花 ID 等超过 JS Number.MAX_SAFE_INTEGER 的 Long，必须以字符串输出；
 * 通过 {@link JsonMapperBuilderCustomizer} 定制自动装配的 {@code JsonMapper}，
 * 确保 HttpMessageConverter / SpringDoc 共用同一套序列化规则。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("longAsString");
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.addModule(module);
        };
    }
}
