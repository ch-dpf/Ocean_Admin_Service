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

    /** 操作发生时的系统存储文件名快照。 */
    private String storageName;

    /** 操作发生时的相对存储路径或对象存储 Key 快照。 */
    private String storageKey;

    /** 操作发生时的存储类型快照。 */
    private String storageType;

    /** 操作发生时的文件扩展名快照。 */
    private String extension;

    /** PENDING、RUNNING、SUCCESS、FAILED。 */
    private String operationStatus;

    /** 本次文件操作的错误摘要。 */
    private String errorMessage;

    /** 操作发生时的文件大小快照。 */
    private Long sizeBytes;

    /** 操作发生时的文件 SHA-256 快照。 */
    private String sha256;

    /** 操作发生时的上传人快照。 */
    private Long uploadedBy;

    /** 操作发生时的上传状态快照。 */
    private String uploadStatus;

    /** 操作发生时的失败资源清理状态快照。 */
    private String cleanupStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 固化文件操作发生时的元数据，避免历史记录受文件后续修改或删除影响。 */
    public void captureFileMetaSnapshot(GisFileMeta fileMeta) {
        originalName = fileMeta.getOriginalName();
        storageName = fileMeta.getStorageName();
        storageKey = fileMeta.getStorageKey();
        storageType = fileMeta.getStorageType();
        extension = fileMeta.getExtension();
        sizeBytes = fileMeta.getSizeBytes() == null ? 0L : fileMeta.getSizeBytes();
        sha256 = fileMeta.getSha256();
        uploadedBy = fileMeta.getUploadedBy();
        uploadStatus = fileMeta.getUploadStatus();
        cleanupStatus = fileMeta.getCleanupStatus();
        errorMessage = fileMeta.getErrorMessage();
        createTime = fileMeta.getCreateTime();
        updateTime = fileMeta.getUpdateTime();
    }
}
