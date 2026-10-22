package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 图层元信息及当前可编辑要素的汇总投影。 */
@Data
public class GisVectorLayer {
    private Long id;
    private Long dataSetId;
    private Long sourceFileId;
    private String layerKey;
    private String layerName;
    private String layerStatus;
    private Long featureCount;
    private String geographicExtent;
    private String geometryTypes;
    private Long dataVersion;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
