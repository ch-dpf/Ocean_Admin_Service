package org.ocean.admin.platform.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 日志查询VO
 *
 * @author DeepSea
 * @since 2026-04-16
 */
@Data
@Schema(description = "日志查询条件")
public class LogQueryVO implements Serializable {

    private static final long SerialVersionUID = 1L;

    /**
     * 用户名（模糊查询）
     */
    @Schema(description = "用户名")
    private String username;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private String startTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private String endTime;

    /**
     * 状态: 0-失败, 1-成功
     */
    @Schema(description = "状态: 0-失败, 1-成功")
    private Integer status;

    /**
     * 当前页码
     */
    @Schema(description = "当前页码", example = "1")
    private Integer current = 1;

    /**
     * 每页条数
     */
    @Schema(description = "每页条数", example = "10")
    private Integer size = 10;
}
