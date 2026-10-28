package org.ocean.admin.kernel.realtime;

/** 实时事件发布契约；业务生产者不依赖连接协议。 */
public interface RealtimeEventPublisher {
    void publish(EventMessage<?> message);

    /** 判断是否有匹配事件类型的订阅者，避免无人订阅时进行昂贵采集。 */
    boolean hasSubscribers(String eventType);
}
