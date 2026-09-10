package org.ocean.admin.platform.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统异常日志VO
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Data
@Schema(description = "系统异常日志")
public class SysExceptionLogVO implements Serializable {

    private static final long SerialVersionUID = 1L;

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 异常类型
     */
    @Schema(description = "异常类型")
    private String exceptionType;

    /**
     * 异常消息
     */
    @Schema(description = "异常消息")
    private String exceptionMessage;

    /**
     * 请求URL
     */
    @Schema(description = "请求URL")
    private String requestUrl;

    /**
     * 请求方式
     */
    @Schema(description = "请求方式")
    private String requestMethod;

    /**
     * 请求参数
     */
    @Schema(description = "请求参数")
    private String requestParams;

    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ipAddress;

    /**
     * 类方法
     */
    @Schema(description = "类方法")
    private String classMethod;

    /**
     * 行号
     */
    @Schema(description = "行号")
    private Integer lineNumber;

    /**
     * 堆栈信息
     */
    @Schema(description = "堆栈信息")
    private String stackTrace;

    /**
     * 处理状态: 0-未处理, 1-已处理
     */
    @Schema(description = "处理状态: 0-未处理, 1-已处理")
    private Integer status;

    /**
     * 处理备注
     */
    @Schema(description = "处理备注")
    private String handleRemark;

    /**
     * 处理时间
     */
    @Schema(description = "处理时间")
    private LocalDateTime handleTime;

    /**
     * 处理人
     */
    @Schema(description = "处理人")
    private String handleUser;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
