package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次静态瓦片生成任务及其不可变处理参数快照。 */
@Data
@TableName(value = "gis_processing_task", schema = "ocean_gis")
public class GisProcessingTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String taskNo;
    private String taskName;
    private String processingType;
    private String sourceType;

    /** 数据库 JSONB 字段，由 Mapper 显式转换。 */
    @TableField(exist = false)
    private String parametersJson;

    private Integer parameterSchemaVersion;
    private String requestFingerprint;
    private Integer priority;
    private Long totalCount;
    private Long completedCount;
    private Long failedCount;
    private String taskStatus;
    private String currentStage;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
