package org.ocean.admin.kernel.realtime;

/**
 * 实时事件发布；业务生产者不依赖连接协议。
 */
public interface RealtimeEventPublisher {
    /**
     * 发布事件消息。
     * @param message 事件消息
     */
    void publish(EventMessage<?> message);

    /**
     * 判断是否有匹配事件类型的订阅者
     * @param eventType
     * @return
     */
    boolean hasSubscribers(String eventType);
}
