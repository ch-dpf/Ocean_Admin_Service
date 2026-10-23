package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 数据集创建时的不可变模板快照及空间、显示状态修订号。 */
@Data
public class GisVectorDataSetStyle {
    private Long id;
    private Long dataSetId;
    private String templateId;
    private Integer templateVersion;
    private Integer schemaVersion;
    private String definitionJson;
    private String templateChecksum;
    private Long styleVersion;
    private Long dataRevision;
    private Long displayRevision;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
