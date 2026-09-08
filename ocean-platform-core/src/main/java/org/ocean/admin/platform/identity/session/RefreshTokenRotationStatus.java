package org.ocean.admin.platform.identity.session;

/** 刷新令牌状态机的可审计结果。 */
public enum RefreshTokenRotationStatus {
    ROTATED,
    REUSE_DETECTED,
    EXPIRED,
    REVOKED,
    INVALID
}
