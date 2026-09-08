package org.ocean.admin.kernel.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 跨模块传递的领域事件基础契约。
 * 事件标识用于幂等与追踪，发生时间表示业务事实产生的时刻。
 */
public interface DomainEvent {
    /** 返回全局唯一的事件标识。 */
    UUID eventId();

    /** 返回事件发生时间，而非事件被消费的时间。 */
    Instant occurredAt();
}
