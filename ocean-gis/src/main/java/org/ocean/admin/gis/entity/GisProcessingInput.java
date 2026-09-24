package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处理任务的不可变输入快照。 */
@Data
@TableName(value = "gis_processing_input", schema = "ocean_gis")
public class GisProcessingInput {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Integer sequenceNo;
    private String inputKind;
    private String inputStatus;
    private Long fileMetaId;
    private String workspaceCode;
    private String relativePath;
    private String storageKey;
    private String originalName;
    private String extension;
    private Long sizeBytes;
    private String sha256;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
