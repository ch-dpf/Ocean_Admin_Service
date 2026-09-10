package org.ocean.admin.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户实体
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Data
@TableName(value = "sys_user", schema = "ocean_platform")
public class SysUser implements Serializable {

    private static final long SerialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码(加密)")
    private String password;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像URL")
    private String avatar;

    @Schema(description = "状态: 0-禁用, 1-启用")
    private Integer status;

    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginTime;

    @Schema(description = "最后登录IP")
    private String lastLoginIp;

    @Schema(description = "允许同时登录设备数")
    private Integer maxLoginDevices;

    @Schema(description = "有效期开始时间")
    private LocalDateTime validFrom;

    @Schema(description = "有效期结束时间")
    private LocalDateTime validTo;

    @Schema(description = "是否永久有效: 0-否, 1-是")
    private Integer isPermanentValid;

    @Schema(description = "密码连续错误次数")
    private Integer failedPasswordAttempts;

    @Schema(description = "账号锁定截止时间")
    private LocalDateTime lockUntil;

    @Schema(description = "锁定等级")
    private Integer lockLevel;

    @Schema(description = "最后一次密码错误时间")
    private LocalDateTime lastPasswordErrorTime;

    @Schema(description = "管理员手动解锁时间")
    private LocalDateTime manualUnlockTime;

    @Schema(description = "管理员手动解锁操作人")
    private String manualUnlockBy;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;

    @TableField(exist = false)
    @Schema(description = "角色编码列表")
    private List<String> roleCodes;

    @TableField(exist = false)
    @Schema(description = "角色类型")
    private String roleType;

    @TableField(exist = false)
    @Schema(description = "可登录平台编码列表")
    private List<String> platformCodes;

    @TableField(exist = false)
    @Schema(description = "当前在线设备数量")
    private Integer onlineDeviceCount;


}
