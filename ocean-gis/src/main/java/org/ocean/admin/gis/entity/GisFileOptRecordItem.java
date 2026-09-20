package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** GIS 文件操作批次中的单文件处理明细。 */
@Data
@TableName(value = "gis_file_opt_record_item", schema = "ocean_gis")
public class GisFileOptRecordItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属操作批次。 */
    private Long recordId;

    /** 对应文件元数据；文件形成前失败时允许为空。 */
    private Long fileMetaId;

    /** 文件在批次中的顺序。 */
    private Integer sequenceNo;

    /** 操作发生时的原始文件名快照。 */
    private String originalName;

    /** PENDING、RUNNING、SUCCESS、FAILED。 */
    private String operationStatus;

    /** 本次文件操作的错误摘要。 */
    private String errorMessage;

    /** 操作发生时的文件大小快照。 */
    private Long sizeBytes;

    private LocalDateTime createTime;
}
