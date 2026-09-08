package org.ocean.admin.platform.identity.session;

import java.util.UUID;

/** 轮换结果；仅 ROTATED 状态包含新令牌的数据库标识。 */
public record RefreshTokenRotation(
        RefreshTokenRotationStatus status,
        UUID sessionId,
        UUID tokenFamilyId,
        UUID refreshTokenId,
        long sessionVersion) {

    static RefreshTokenRotation invalid() {
        return new RefreshTokenRotation(RefreshTokenRotationStatus.INVALID, null, null, null, -1);
    }
}
