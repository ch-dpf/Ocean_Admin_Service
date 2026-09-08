package org.ocean.admin.platform.api;

import org.ocean.admin.kernel.security.CurrentUser;

/**
 * 获取当前认证用户的公共扩展点，供 {@code ocean-business-*} 等业务模块使用。
 */
public interface CurrentUserAccessor {
    /**
     * 返回当前用户；调用时必须已经完成认证。
     *
     * @return 当前认证用户及其平台、角色和权限上下文
     */
    CurrentUser requiredCurrentUser();
}
