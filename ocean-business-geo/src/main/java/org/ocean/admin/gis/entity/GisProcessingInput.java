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
    /** 任务执行期间使用的实时文件引用；任务进入终态或源文件删除后置空。 */
    private Long fileMetaId;
    /** 数据管理文件提交任务时保存的不可变来源快照。 */
    private Long sourceFileMetaId;
    private Long sourceDataSetId;
    private String sourceDataSetName;
    private String sourceStorageType;
    private String sourceStorageKey;
    private String sourceLocatorType;
    private Long sourceModifiedAt;
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
