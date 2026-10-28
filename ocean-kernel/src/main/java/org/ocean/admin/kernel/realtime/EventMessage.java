package org.ocean.admin.kernel.realtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 事件消息
 */
public record EventMessage<T>(int schemaVersion, String messageId, String type, String source,
                              String subject, Instant occurredAt, String correlationId, T data) {
    public EventMessage {
        if (schemaVersion < 1) throw new IllegalArgumentException("消息协议版本无效");
        for (String value : new String[]{messageId, type, source, subject})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("消息身份与类型不能为空");
        Objects.requireNonNull(occurredAt, "事件时间不能为空");
        Objects.requireNonNull(data, "消息体不能为空");
    }

    /**
     * 创建事件消息
     * @param type 事件类型
     * @param source 事件源
     * @param subject 事件主题
     * @param correlationId 联系ID
     * @param data 事件数据
     * @return 事件消息
     */
    public static <T> EventMessage<T> create(String type, String source, String subject,
                                            String correlationId, T data) {
        return new EventMessage<>(1, UUID.randomUUID().toString(), type, source, subject,
                Instant.now(), correlationId, data);
    }
}
