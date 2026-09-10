package org.ocean.admin.platform.identity.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Slf4j
@Component
public class JwtUtil {
    /**
     * JWT密钥
     */
    private static final String SECRET_KEY = "ocean-admin-secret-key-2026-deep-sea-technology-co-ltd";

    /**
     * 过期时间：7天
     */
    private static final long EXPIRATION_TIME = 7 * 24 * 60 * 60 * 1000;

    /**
     * 生成JWT Token
     *
     * @param username 用户名
     * @param userId 用户ID
     * @return JWT Token
     */
    public String generateToken(String username, Long userId, String sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("userId", userId);
        claims.put("sessionId", sessionId);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析JWT Token
     *
     * @param token JWT Token
     * @return Claims
     */
    public Claims parseToken(String token) {
        try {
            return parseTokenStrict(token);
        } catch (Exception e) {
            log.error("解析JWT Token失败: {}", e.getMessage());
            return null;
        }
    }

    public Claims parseTokenStrict(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从Token中获取用户名
     *
     * @param token JWT Token
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * 从Token中获取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("userId", Long.class) : null;
    }

    public String getSessionIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("sessionId", String.class) : null;
    }

    /**
     * 验证Token是否有效
     *
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseTokenStrict(token);
            return claims != null && !isTokenExpired(claims);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * JWT 令牌状态判定
     * @param token 令牌
     * @return 令牌状态
     */
    public String classifyTokenStatus(String token) {
        if (token == null || token.isBlank()) {
            return "missing_token";     //  token 为空或空白
        }
        try {
            Claims claims = parseTokenStrict(token);
            if (claims == null || isTokenExpired(claims)) {
                return "expired";   // 	已过期
            }
            return "ok";    //  解析成功且未过期
        } catch (ExpiredJwtException e) {
            return "expired";   //  已过期
        } catch (SecurityException e) {
            return "signature_invalid";    //   签名校验失败
        } catch (MalformedJwtException | UnsupportedJwtException | IllegalArgumentException e) {
            return "malformed";     //  格式错误、不支持、参数非法
        } catch (Exception e) {
            return "unknown_error";     //  未知异常
        }
    }

    /**
     * 获取过期时间戳（秒）
     * @param token 令牌
     * @return 过期时间戳（秒）
     */
    public Long getExpirationEpochSeconds(String token) {
        Claims claims = parseToken(token);
        if (claims == null || claims.getExpiration() == null) {
            return null;
        }
        return Instant.ofEpochMilli(claims.getExpiration().getTime()).getEpochSecond();
    }

    /**
     * 获取过期时间戳（秒）
     * @return 过期时间戳（秒）
     */
    public long getExpirationTimeSeconds() {
        return EXPIRATION_TIME / 1000;
    }

    /**
     * 检查Token是否过期
     *
     * @param claims Claims
     * @return 是否过期
     */
    private boolean isTokenExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration.before(new Date());
    }

    /**
     * 获取签名密钥
     * @return SecretKey
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = SECRET_KEY.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
