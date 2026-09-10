package org.ocean.admin.platform.audit.utils;


import org.ocean.admin.kernel.common.ResponseResult;

/**
 * 日志控制器响应封装，统一常见的成功/失败返回模式。
 */
public final class LogResponseSupport {

    private LogResponseSupport() {
    }

    public static <T> ResponseResult<T> successOrNotFound(T data, String notFoundMessage) {
        return data == null ? ResponseResult.error(notFoundMessage) : ResponseResult.success(data);
    }

    public static ResponseResult<Boolean> booleanResult(boolean success, String errorMessage) {
        return success ? ResponseResult.success(true) : ResponseResult.error(errorMessage);
    }
}

