package org.ocean.admin.platform.identity.session;

import java.util.UUID;

/** 刷新令牌呈现校验结果；无匹配令牌时标识字段为空。 */
public record RefreshTokenPresentation(
        RefreshTokenPresentationStatus status,
        UUID sessionId,
        UUID tokenFamilyId,
        long sessionVersion) {

    static RefreshTokenPresentation invalid() {
        return new RefreshTokenPresentation(RefreshTokenPresentationStatus.INVALID, null, null, -1);
    }
}
