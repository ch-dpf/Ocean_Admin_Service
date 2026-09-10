package org.ocean.admin.platform.audit.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.platform.audit.entity.SysLoginLog;
import org.ocean.admin.platform.audit.service.SysLoginLogService;
import org.ocean.admin.platform.audit.utils.UserAgentParser;
import org.ocean.admin.platform.identity.event.UserLoginEvent;
import org.ocean.admin.platform.identity.event.UserLogoutEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 将身份域登录事件转换为审计日志并持久化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserLoginAuditListener {

    private final SysLoginLogService loginLogService;

    @EventListener
    public void onUserLogin(UserLoginEvent event) {
        try {
            SysLoginLog loginLog = new SysLoginLog();
            loginLog.setUserId(event.userId());
            loginLog.setUsername(normalizeUsername(event.username()));
            loginLog.setLoginType("PASSWORD");
            loginLog.setStatus(event.success() ? 1 : 0);
            loginLog.setMessage(event.message());
            loginLog.setLoginTime(event.occurredAt());
            loginLog.setSessionId(event.sessionId());
            loginLog.setPlatform(event.platform());
            loginLog.setDeviceId(event.deviceId());
            loginLog.setIpAddress(event.ipAddress());
            loginLog.setUserAgent(event.userAgent());
            loginLog.setBrowser(firstNonBlank(event.browser(), UserAgentParser.parseBrowser(event.userAgent())));
            loginLog.setOs(firstNonBlank(event.os(), UserAgentParser.parseOS(event.userAgent())));
            loginLogService.recordLoginLog(loginLog);
        } catch (Exception e) {
            log.error("记录登录审计日志失败: {}", e.getMessage(), e);
        }
    }

    @EventListener
    public void onUserLogout(UserLogoutEvent event) {
        try {
            loginLogService.updateLogoutTime(event.sessionId(), event.occurredAt());
        } catch (Exception e) {
            log.error("更新登出审计日志失败: {}", e.getMessage(), e);
        }
    }

    private String normalizeUsername(String username) {
        return username == null || username.isBlank() ? "unknown" : username.trim();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred.trim();
    }
}
