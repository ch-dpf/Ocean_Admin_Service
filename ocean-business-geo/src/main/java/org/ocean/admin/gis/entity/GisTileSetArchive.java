package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.ocean.admin.gis.dto.TaskStatus;

import java.time.LocalDateTime;

/** 静态瓦片集的可下载归档及其生成状态。 */
@Data
@TableName(value = "gis_tile_set_archive", schema = "ocean_gis")
public class GisTileSetArchive {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 实时进度流代次，与业务状态版本分开维护。 */
    private Long progressEpoch = 0L;
    private String archiveNo;
    private Long processingTaskId;
    private Long tileSetId;
    private String archiveName;
    private String archiveFormat;
    private TaskStatus archiveStatus;
    private String currentStage;
    private Long totalFileCount;
    private Long processedFileCount;
    private Long totalBytes;
    private Long processedBytes;
    private Long archiveSizeBytes;
    private String storageKey;
    private String sha256;
    private Long operatorId;
    private Long downloadCount;
    private LocalDateTime lastDownloadTime;
    private String errorMessage;
    private Long version;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
