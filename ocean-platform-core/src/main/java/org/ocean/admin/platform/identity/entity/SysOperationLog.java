package org.ocean.admin.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统操作日志实体
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Data
@TableName("sys_operation_log")
@Schema(description = "系统操作日志")
public class SysOperationLog implements Serializable {

    private static final long SerialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

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
     * 操作模块
     */
    @Schema(description = "操作模块")
    private String module;

    /**
     * 操作类型: INSERT/UPDATE/DELETE/QUERY/EXPORT/IMPORT/LOGIN/LOGOUT
     */
    @Schema(description = "操作类型")
    private String operationType;

    /**
     * 操作描述
     */
    @Schema(description = "操作描述")
    private String description;

    /**
     * 请求方法
     */
    @Schema(description = "请求方法")
    private String method;

    /**
     * 请求URL
     */
    @Schema(description = "请求URL")
    private String requestUrl;

    /**
     * 请求方式: GET/POST/PUT/DELETE
     */
    @Schema(description = "请求方式")
    private String requestMethod;

    /**
     * 请求参数
     */
    @Schema(description = "请求参数")
    private String requestParams;

    /**
     * 响应结果
     */
    @Schema(description = "响应结果")
    private String responseResult;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ipAddress;

    /**
     * 浏览器标识
     */
    @Schema(description = "浏览器标识")
    private String userAgent;

    /**
     * 执行时长(毫秒)
     */
    @Schema(description = "执行时长(毫秒)")
    private Long executionTime;

    /**
     * 状态: 0-失败, 1-成功
     */
    @Schema(description = "状态: 0-失败, 1-成功")
    private Integer status;

    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 逻辑删除: 0-未删除, 1-已删除
     */
    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;
}

