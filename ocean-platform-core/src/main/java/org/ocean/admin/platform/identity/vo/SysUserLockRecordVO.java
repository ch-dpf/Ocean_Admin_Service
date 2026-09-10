package org.ocean.admin.platform.identity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "用户锁定记录")
public class SysUserLockRecordVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String username;
    private String realName;
    private String recordType;
    private Integer failedPasswordAttempts;
    private Integer lockLevel;
    private LocalDateTime lockUntil;
    private Integer lockMinutes;
    private String message;
    private String operatorName;
    private LocalDateTime createTime;
}
