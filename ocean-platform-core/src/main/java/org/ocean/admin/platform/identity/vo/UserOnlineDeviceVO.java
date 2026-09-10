package org.ocean.admin.platform.identity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "用户在线设备信息")
public class UserOnlineDeviceVO {

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "平台")
    private String platform;

    @Schema(description = "IP地址")
    private String ipAddress;

    @Schema(description = "浏览器")
    private String browser;

    @Schema(description = "操作系统")
    private String os;

    @Schema(description = "登录时间")
    private LocalDateTime loginTime;

    @Schema(description = "最后活跃时间")
    private LocalDateTime lastActiveTime;

    @Schema(description = "剩余过期秒数")
    private Long ttlSeconds;
}
