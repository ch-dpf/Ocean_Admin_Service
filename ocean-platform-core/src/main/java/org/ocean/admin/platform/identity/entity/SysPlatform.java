package org.ocean.admin.platform.identity.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统平台实体
 *
 * @author DeepOcean
 * @since 2026-09-09
 */
@Data
@TableName(value = "sys_platform", schema = "ocean_platform")
@Schema(description = "系统平台")
public class SysPlatform implements Serializable {

    private static final long SerialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "平台编码")
    private String platformCode;

    @Schema(description = "平台名称")
    private String platformName;

    @Schema(description = "平台描述")
    private String description;

    @Schema(description = "平台地址")
    private String platformUrl;

    @Schema(description = "平台图标")
    private String icon;

    @Schema(description = "状态: 0-禁用, 1-启用")
    private Integer status;

    @Schema(description = "排序")
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;
}
