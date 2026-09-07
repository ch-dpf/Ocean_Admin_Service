package org.ocean.admin.platform.api;

import java.util.Map;

/** Business modules publish sanitized audit facts through this boundary. */
public interface AuditPublisher {
    void publish(String category, String eventType, String resourceType,
                 String resourceId, String outcome, Map<String, Object> summary);
}

