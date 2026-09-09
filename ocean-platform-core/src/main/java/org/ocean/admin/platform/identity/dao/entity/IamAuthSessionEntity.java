package org.ocean.admin.platform.identity.dao.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 供会话管理接口读取的认证会话投影。 */
@TableName(value = "ocean_platform.iam_auth_session", autoResultMap = true)
public class IamAuthSessionEntity {
    @TableId("session_id")
    private UUID sessionId;
    @TableField("user_id")
    private UUID userId;
    @TableField("platform_id")
    private UUID platformId;
    private String deviceId;
    private String deviceName;
    private String clientIp;
    private String userAgent;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActiveAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime refreshExpiresAt;
    private OffsetDateTime revokedAt;
    private String revokeReason;
    private Long version;

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getPlatformId() { return platformId; }
    public void setPlatformId(UUID platformId) { this.platformId = platformId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(OffsetDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getRefreshExpiresAt() { return refreshExpiresAt; }
    public void setRefreshExpiresAt(OffsetDateTime refreshExpiresAt) { this.refreshExpiresAt = refreshExpiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
