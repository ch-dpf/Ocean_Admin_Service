package org.ocean.admin.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 类的功能描述
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Data
@TableName("sys_user_lock_record")
@Schema(description = "用户锁定记录")
public class SysUserLockRecord implements Serializable {

    private static final long SerialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "记录类型: LOCK/UNLOCK")
    private String recordType;

    @Schema(description = "密码错误次数")
    private Integer failedPasswordAttempts;

    @Schema(description = "锁定等级")
    private Integer lockLevel;

    @Schema(description = "锁定截止时间")
    private LocalDateTime lockUntil;

    @Schema(description = "锁定分钟数")
    private Integer lockMinutes;

    @Schema(description = "描述信息")
    private String message;

    @Schema(description = "操作人")
    private String operatorName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;


}
