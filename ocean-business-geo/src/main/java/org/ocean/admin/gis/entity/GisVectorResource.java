package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 独立于原始文件的矢量存储版本；只有 READY 版本可被读取和发布。 */
@Data
public class GisVectorResource {
    private Long id;
    private Long dataSetId;
    private Long sourceFileId;
    private String resourceKind;
    private String storageType;
    private String storageKey;
    private String status;
    private Long dataVersion;
    private Long sizeBytes;
    private String checksum;
    private String definitionJson;
    private LocalDateTime createTime;
}
