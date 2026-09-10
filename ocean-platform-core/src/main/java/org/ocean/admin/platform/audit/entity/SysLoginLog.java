package org.ocean.admin.platform.audit.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统登录日志实体
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Data
@TableName(value = "sys_login_log", schema = "ocean_platform")
@Schema(description = "系统登录日志")
public class SysLoginLog implements Serializable {

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
     * 登录类型: PASSWORD/CODE/TOKEN
     */
    @Schema(description = "登录类型")
    private String loginType;

    /**
     * IP地址
     */
    @Schema(description = "IP地址")
    private String ipAddress;

    /**
     * 登录地点
     */
    @Schema(description = "登录地点")
    private String location;

    /**
     * 浏览器
     */
    @Schema(description = "浏览器")
    private String browser;

    /**
     * 操作系统
     */
    @Schema(description = "操作系统")
    private String os;

    /**
     * 用户代理
     */
    @Schema(description = "用户代理")
    private String userAgent;

    /**
     * 设备ID
     */
    @Schema(description = "设备ID")
    private String deviceId;

    /**
     * 登录平台
     */
    @Schema(description = "登录平台")
    private String platform;

    /**
     * 状态: 0-失败, 1-成功
     */
    @Schema(description = "状态: 0-失败, 1-成功")
    private Integer status;

    /**
     * 提示信息
     */
    @Schema(description = "提示信息")
    private String message;

    /**
     * 登录时间
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "登录时间")
    private LocalDateTime loginTime;

    /**
     * 登出时间
     */
    @Schema(description = "登出时间")
    private LocalDateTime logoutTime;

    /**
     * 会话ID
     */
    @Schema(description = "会话ID")
    private String sessionId;

    /**
     * 逻辑删除: 0-未删除, 1-已删除
     */
    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;
}

