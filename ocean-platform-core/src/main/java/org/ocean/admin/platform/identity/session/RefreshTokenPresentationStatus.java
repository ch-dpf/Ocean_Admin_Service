package org.ocean.admin.platform.identity.session;

/** 刷新令牌在不执行轮换时的数据库校验结果。 */
public enum RefreshTokenPresentationStatus {
    ACTIVE,
    REUSE_DETECTED,
    EXPIRED,
    REVOKED,
    INVALID
}
