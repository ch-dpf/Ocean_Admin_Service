package org.ocean.admin.platform.identity.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 以 PostgreSQL 为唯一权威源管理会话、刷新令牌轮换和令牌族撤销。 */
@Service
public class RefreshTokenLifecycleService {

    private static final int MAX_TOKEN_LENGTH = 8_192;
    private static final String REUSE_REASON = "REFRESH_TOKEN_REUSE";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final SessionStateCache sessionStateCache;

    @Autowired
    public RefreshTokenLifecycleService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            SessionStateCache sessionStateCache) {
        this(jdbcTemplate, transactionManager, Clock.systemUTC(), sessionStateCache);
    }

    /** 允许测试注入固定时钟；生产装配使用 UTC 系统时钟。 */
    public RefreshTokenLifecycleService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(jdbcTemplate, transactionManager, clock, SessionStateCache.noOp());
    }

    RefreshTokenLifecycleService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SessionStateCache sessionStateCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionStateCache = Objects.requireNonNull(sessionStateCache, "sessionStateCache");
    }

    /** 在同一事务中创建会话和第 0 枚刷新令牌。 */
    public IssuedSession issue(SessionIssueCommand command, String refreshToken) {
        return issue(command, refreshToken, UUID.randomUUID());
    }

    /** 使用协议层提供的稳定会话标识创建会话，供访问令牌 sid 与状态记录关联。 */
    public IssuedSession issue(
            SessionIssueCommand command, String refreshToken, UUID sessionId) {
        validateIssue(command);
        Objects.requireNonNull(sessionId, "sessionId");
        String tokenHash = hash(refreshToken);
        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        IssuedSession issued = transactionTemplate.execute(status -> {
            jdbcTemplate.update("""
                    INSERT INTO ocean_platform.iam_auth_session (
                        session_id, user_id, platform_id, oauth_client_id,
                        device_id, device_name, client_ip, user_agent,
                        token_family_id, created_at, last_active_at, expires_at,
                        refresh_expires_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, sessionId, command.userId(), command.platformId(), command.oauthClientId(),
                    trimmed(command.deviceId(), 200), trimmed(command.deviceName(), 200),
                    trimmed(command.clientIp(), 64), trimmed(command.userAgent(), 1000), familyId,
                    atOffset(now), atOffset(now), atOffset(command.sessionExpiresAt()),
                    atOffset(command.refreshTokenExpiresAt()));
            jdbcTemplate.update("""
                    INSERT INTO ocean_platform.iam_refresh_token (
                        token_id, session_id, token_family_id, token_hash,
                        sequence_number, status, issued_at, expires_at, version
                    ) VALUES (?, ?, ?, ?, 0, 'ISSUED', ?, ?, 0)
                    """, tokenId, sessionId, familyId, tokenHash,
                    atOffset(now), atOffset(command.refreshTokenExpiresAt()));
            return new IssuedSession(sessionId, familyId, tokenId, 0);
        });
        IssuedSession result = Objects.requireNonNull(issued, "transaction result");
        afterCommit(() -> refreshCacheFromDatabase(result.sessionId()));
        return result;
    }

    /**
     * 消费当前刷新令牌并签发下一枚。对已消费令牌的再次使用会提交令牌族撤销，
     * 因而此方法以结果值表示拒绝，不能通过抛异常回滚安全状态。
     */
    public RefreshTokenRotation rotate(
            String presentedRefreshToken,
            String replacementRefreshToken,
            Instant replacementExpiresAt) {
        String presentedHash = hash(presentedRefreshToken);
        String replacementHash = hash(replacementRefreshToken);
        if (presentedHash.equals(replacementHash)) {
            throw new IllegalArgumentException("Replacement refresh token must be different");
        }
        Objects.requireNonNull(replacementExpiresAt, "replacementExpiresAt");
        RefreshTokenRotation result = transactionTemplate.execute(status ->
                rotateInTransaction(presentedHash, replacementHash, replacementExpiresAt));
        RefreshTokenRotation rotation = Objects.requireNonNull(result, "transaction result");
        if (rotation.sessionId() != null) {
            if (rotation.status() == RefreshTokenRotationStatus.ROTATED) {
                afterCommit(() -> refreshCacheFromDatabase(rotation.sessionId()));
            } else {
                afterCommit(() -> sessionStateCache.evict(rotation.sessionId()));
            }
        }
        return rotation;
    }

    /**
     * 在 SAS 读取授权前校验令牌状态。已消费令牌再次出现时在本事务中撤销整个令牌族，
     * 使旧令牌即使已从 SAS 官方授权表中被覆盖，也仍能触发重放检测。
     */
    public RefreshTokenPresentation validatePresentation(String refreshToken) {
        String tokenHash = hash(refreshToken);
        RefreshTokenPresentation result = transactionTemplate.execute(status -> {
            TokenState current = findForUpdate(tokenHash);
            if (current == null) {
                return RefreshTokenPresentation.invalid();
            }
            if (current.revokedAt() != null || "REVOKED".equals(current.status())) {
                return presented(RefreshTokenPresentationStatus.REVOKED, current, current.sessionVersion());
            }
            if ("USED".equals(current.status())) {
                long version = revokeFamily(current, REUSE_REASON);
                return presented(RefreshTokenPresentationStatus.REUSE_DETECTED, current, version);
            }
            Instant now = clock.instant();
            if (!current.expiresAt().isAfter(now) || !current.sessionExpiresAt().isAfter(now)) {
                long version = revokeFamily(current, "REFRESH_TOKEN_EXPIRED");
                return presented(RefreshTokenPresentationStatus.EXPIRED, current, version);
            }
            return presented(RefreshTokenPresentationStatus.ACTIVE, current, current.sessionVersion());
        });
        RefreshTokenPresentation presentation = Objects.requireNonNull(result, "transaction result");
        if (presentation.sessionId() != null
                && presentation.status() != RefreshTokenPresentationStatus.ACTIVE) {
            afterCommit(() -> sessionStateCache.evict(presentation.sessionId()));
        }
        return presentation;
    }

    /** 按刷新令牌撤销其所属会话；供 RFC 7009 撤销和 OIDC 注销适配器调用。 */
    public boolean revokeByRefreshToken(String refreshToken, String reason) {
        String tokenHash = hash(refreshToken);
        String revokeReason = requiredReason(reason);
        UUID revokedSessionId = transactionTemplate.execute(status -> {
            TokenState current = findForUpdate(tokenHash);
            if (current == null || current.revokedAt() != null) {
                return null;
            }
            revokeFamily(current, revokeReason);
            return current.sessionId();
        });
        if (revokedSessionId != null) {
            afterCommit(() -> sessionStateCache.evict(revokedSessionId));
        }
        return revokedSessionId != null;
    }

    /** 幂等撤销一个会话及其仍活跃的刷新令牌。 */
    public boolean revokeSession(UUID sessionId, String reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        String revokeReason = requiredReason(reason);
        Boolean revoked = transactionTemplate.execute(status -> {
            OffsetDateTime now = atOffset(clock.instant());
            int sessionRows = jdbcTemplate.update("""
                    UPDATE ocean_platform.iam_auth_session
                       SET revoked_at = ?, revoke_reason = ?, version = version + 1
                     WHERE session_id = ? AND revoked_at IS NULL
                    """, now, revokeReason, sessionId);
            jdbcTemplate.update("""
                    UPDATE ocean_platform.iam_refresh_token
                       SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?, version = version + 1
                     WHERE session_id = ? AND status = 'ISSUED'
                    """, now, revokeReason, sessionId);
            return sessionRows == 1;
        });
        boolean result = Boolean.TRUE.equals(revoked);
        if (result) {
            afterCommit(() -> sessionStateCache.evict(sessionId));
        }
        return result;
    }

    /** Redis 优先读取活跃会话；未命中或缓存不可用时回源 PostgreSQL 并回填。 */
    public Optional<SessionState> findActiveSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Optional<SessionState> cached = sessionStateCache.find(sessionId);
        if (cached.isPresent() && cached.get().sessionExpiresAt().isAfter(clock.instant())
                && cached.get().refreshTokenExpiresAt().isAfter(clock.instant())) {
            return cached;
        }
        return loadActiveSessionFromDatabase(sessionId, true);
    }

    private Optional<SessionState> loadActiveSessionFromDatabase(
            UUID sessionId, boolean populateCache) {
        OffsetDateTime now = atOffset(clock.instant());
        List<SessionState> states = jdbcTemplate.query("""
                SELECT session_id, token_family_id, version, expires_at, refresh_expires_at
                  FROM ocean_platform.iam_auth_session
                 WHERE session_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                   AND refresh_expires_at > ?
                """, (resultSet, rowNumber) -> new SessionState(
                resultSet.getObject("session_id", UUID.class),
                resultSet.getObject("token_family_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("refresh_expires_at", OffsetDateTime.class).toInstant()),
                sessionId, now, now);
        Optional<SessionState> state = states.stream().findFirst();
        if (populateCache) {
            state.ifPresentOrElse(sessionStateCache::put, () -> sessionStateCache.evict(sessionId));
        }
        return state;
    }

    private void refreshCacheFromDatabase(UUID sessionId) {
        loadActiveSessionFromDatabase(sessionId, true);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
            return;
        }
        action.run();
    }

    private RefreshTokenRotation rotateInTransaction(
            String presentedHash, String replacementHash, Instant replacementExpiresAt) {
        TokenState current = findForUpdate(presentedHash);
        if (current == null) {
            return RefreshTokenRotation.invalid();
        }
        if (current.revokedAt() != null) {
            return rejected(RefreshTokenRotationStatus.REVOKED, current, current.sessionVersion());
        }
        if (!"ISSUED".equals(current.status())) {
            long version = revokeFamily(current, REUSE_REASON);
            return rejected(RefreshTokenRotationStatus.REUSE_DETECTED, current, version);
        }

        Instant now = clock.instant();
        if (!current.expiresAt().isAfter(now) || !current.sessionExpiresAt().isAfter(now)) {
            long version = revokeFamily(current, "REFRESH_TOKEN_EXPIRED");
            return rejected(RefreshTokenRotationStatus.EXPIRED, current, version);
        }
        if (!replacementExpiresAt.isAfter(now)
                || replacementExpiresAt.isAfter(current.sessionExpiresAt())) {
            throw new IllegalArgumentException(
                    "Replacement expiry must be in the future and within the session lifetime");
        }

        UUID replacementId = UUID.randomUUID();
        OffsetDateTime usedAt = atOffset(now);
        int tokenRows = jdbcTemplate.update("""
                UPDATE ocean_platform.iam_refresh_token
                   SET status = 'USED', used_at = ?, version = version + 1
                 WHERE token_id = ? AND status = 'ISSUED' AND version = ?
                """, usedAt, current.tokenId(), current.tokenVersion());
        if (tokenRows != 1) {
            throw new ConcurrencyFailureException("Refresh token changed during rotation");
        }
        jdbcTemplate.update("""
                INSERT INTO ocean_platform.iam_refresh_token (
                    token_id, session_id, token_family_id, token_hash,
                    sequence_number, status, issued_at, expires_at, version
                ) VALUES (?, ?, ?, ?, ?, 'ISSUED', ?, ?, 0)
                """, replacementId, current.sessionId(), current.familyId(), replacementHash,
                current.sequenceNumber() + 1, usedAt, atOffset(replacementExpiresAt));
        jdbcTemplate.update("""
                UPDATE ocean_platform.iam_refresh_token
                   SET replaced_by_token_id = ?
                 WHERE token_id = ?
                """, replacementId, current.tokenId());
        int sessionRows = jdbcTemplate.update("""
                UPDATE ocean_platform.iam_auth_session
                   SET last_active_at = ?, refresh_expires_at = ?, version = version + 1
                 WHERE session_id = ? AND version = ? AND revoked_at IS NULL
                """, usedAt, atOffset(replacementExpiresAt),
                current.sessionId(), current.sessionVersion());
        if (sessionRows != 1) {
            throw new ConcurrencyFailureException("Session changed during refresh token rotation");
        }
        return new RefreshTokenRotation(
                RefreshTokenRotationStatus.ROTATED,
                current.sessionId(), current.familyId(), replacementId,
                current.sessionVersion() + 1);
    }

    private TokenState findForUpdate(String tokenHash) {
        List<TokenState> states = jdbcTemplate.query("""
                SELECT t.token_id, t.session_id, t.token_family_id, t.sequence_number,
                       t.status, t.expires_at, t.version AS token_version,
                       s.version AS session_version, s.expires_at AS session_expires_at,
                       s.revoked_at
                  FROM ocean_platform.iam_refresh_token t
                  JOIN ocean_platform.iam_auth_session s ON s.session_id = t.session_id
                 WHERE t.token_hash = ?
                 FOR UPDATE OF t, s
                """, (resultSet, rowNumber) -> new TokenState(
                resultSet.getObject("token_id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getObject("token_family_id", UUID.class),
                resultSet.getLong("sequence_number"),
                resultSet.getString("status"),
                resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("token_version"),
                resultSet.getLong("session_version"),
                resultSet.getObject("session_expires_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("revoked_at", OffsetDateTime.class)), tokenHash);
        return states.isEmpty() ? null : states.getFirst();
    }

    private long revokeFamily(TokenState current, String reason) {
        OffsetDateTime now = atOffset(clock.instant());
        jdbcTemplate.update("""
                UPDATE ocean_platform.iam_refresh_token
                   SET status = 'REVOKED', used_at = NULL, revoked_at = ?,
                       revoke_reason = ?, version = version + 1
                 WHERE token_family_id = ? AND status = 'ISSUED'
                """, now, reason, current.familyId());
        int rows = jdbcTemplate.update("""
                UPDATE ocean_platform.iam_auth_session
                   SET revoked_at = COALESCE(revoked_at, ?),
                       revoke_reason = COALESCE(revoke_reason, ?), version = version + 1
                 WHERE session_id = ? AND version = ?
                """, now, reason, current.sessionId(), current.sessionVersion());
        if (rows != 1) {
            throw new ConcurrencyFailureException("Session changed during token family revocation");
        }
        return current.sessionVersion() + 1;
    }

    private static RefreshTokenRotation rejected(
            RefreshTokenRotationStatus status, TokenState state, long sessionVersion) {
        return new RefreshTokenRotation(
                status, state.sessionId(), state.familyId(), null, sessionVersion);
    }

    private static RefreshTokenPresentation presented(
            RefreshTokenPresentationStatus status, TokenState state, long sessionVersion) {
        return new RefreshTokenPresentation(
                status, state.sessionId(), state.familyId(), sessionVersion);
    }

    private void validateIssue(SessionIssueCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.userId(), "userId");
        Objects.requireNonNull(command.platformId(), "platformId");
        Objects.requireNonNull(command.sessionExpiresAt(), "sessionExpiresAt");
        Objects.requireNonNull(command.refreshTokenExpiresAt(), "refreshTokenExpiresAt");
        Instant now = clock.instant();
        if (!command.refreshTokenExpiresAt().isAfter(now)
                || command.refreshTokenExpiresAt().isAfter(command.sessionExpiresAt())) {
            throw new IllegalArgumentException(
                    "Refresh token expiry must be in the future and within the session lifetime");
        }
    }

    private static String hash(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Refresh token must be non-blank and at most 8192 characters");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String trimmed(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > maximumLength) {
            throw new IllegalArgumentException("Session metadata exceeds maximum length");
        }
        return stripped.isEmpty() ? null : stripped;
    }

    private static String requiredReason(String reason) {
        String value = trimmed(reason, 200);
        if (value == null) {
            throw new IllegalArgumentException("Revoke reason is required");
        }
        return value;
    }

    private static OffsetDateTime atOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record TokenState(
            UUID tokenId,
            UUID sessionId,
            UUID familyId,
            long sequenceNumber,
            String status,
            Instant expiresAt,
            long tokenVersion,
            long sessionVersion,
            Instant sessionExpiresAt,
            OffsetDateTime revokedAt) {
    }
}
