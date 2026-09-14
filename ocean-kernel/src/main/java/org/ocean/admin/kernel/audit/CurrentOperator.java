package org.ocean.admin.kernel.audit;

/**
 * 已通过认证的当前操作者。由认证入口写入请求上下文，供审计采集使用。
 * 保存当前登录操作者
 */
public record CurrentOperator(Long userId, String username) {

    public static final String REQUEST_ATTRIBUTE = CurrentOperator.class.getName();
}
