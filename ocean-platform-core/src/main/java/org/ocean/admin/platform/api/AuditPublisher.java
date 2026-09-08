package org.ocean.admin.platform.api;

import java.util.Map;

/**
 * 业务模块发布审计事实的统一边界。
 * 调用方必须先移除密码、令牌、Cookie 等敏感信息，再提交摘要。
 */
public interface AuditPublisher {
    /** 发布一条已脱敏的业务审计事件。 */
    void publish(String category, String eventType, String resourceType,
                 String resourceId, String outcome, Map<String, Object> summary);
}
