package org.ocean.admin.platform.identity.session;

import java.util.Optional;
import java.util.UUID;

/** 活跃会话的可丢弃缓存端口；实现不得让缓存故障阻断数据库业务。 */
public interface SessionStateCache {

    Optional<SessionState> find(UUID sessionId);

    void put(SessionState session);

    void evict(UUID sessionId);

    static SessionStateCache noOp() {
        return NoOpHolder.INSTANCE;
    }

    final class NoOpHolder {
        private static final SessionStateCache INSTANCE = new SessionStateCache() {
            @Override
            public Optional<SessionState> find(UUID sessionId) {
                return Optional.empty();
            }

            @Override
            public void put(SessionState session) {
            }

            @Override
            public void evict(UUID sessionId) {
            }
        };

        private NoOpHolder() {
        }
    }
}
