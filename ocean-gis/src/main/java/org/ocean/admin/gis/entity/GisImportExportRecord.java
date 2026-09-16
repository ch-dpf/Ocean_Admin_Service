package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** GIS 文件导入导出记录。 */
@Data
@TableName(value = "gis_import_export_record", schema = "ocean_gis")
public class GisImportExportRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对外暴露的稳定记录编号。 */
    private String recordNo;

    /** IMPORT、EXPORT。 */
    private String operationType;

    /** 关联数据集。 */
    private Long dataSetId;

    /** 工作项总数。 */
    private Integer totalCount;

    /** 成功工作项数量。 */
    private Integer completedCount;

    /** 失败工作项数量。 */
    private Integer failedCount;

    /** QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED。 */
    private String recordStatus;

    /** 当前处理阶段。 */
    private String currentStage;

    /** 导出结果的相对存储路径或对象存储 Key。 */
    private String resultStorageKey;

    /** 批次级错误摘要。 */
    private String errorMessage;

    /** 操作人。 */
    private Long operatorId;

    /** 状态版本号，用于进度快照与实时事件合并。 */
    private Long version;

    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
