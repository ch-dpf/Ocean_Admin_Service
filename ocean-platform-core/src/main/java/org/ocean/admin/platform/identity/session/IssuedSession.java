package org.ocean.admin.platform.identity.session;

import java.util.UUID;

/** 成功签发的会话和首枚刷新令牌标识。 */
public record IssuedSession(UUID sessionId, UUID tokenFamilyId, UUID refreshTokenId, long version) {
}
