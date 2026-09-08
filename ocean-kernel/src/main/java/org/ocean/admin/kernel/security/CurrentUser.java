package org.ocean.admin.kernel.security;

import java.util.Set;
import java.util.UUID;

/**
 * 一次已认证请求中的用户安全上下文。
 * 角色与权限使用集合表达，避免同一授权项重复出现。
 *
 * @param userId 用户唯一标识
 * @param username 登录名
 * @param sessionId 当前认证会话标识
 * @param platformCode 当前访问的平台编码
 * @param roles 当前平台范围内生效的角色编码
 * @param permissions 当前平台范围内生效的权限编码
 */
public record CurrentUser(
        UUID userId,
        String username,
        UUID sessionId,
        String platformCode,
        Set<String> roles,
        Set<String> permissions
) {
}
