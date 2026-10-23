package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 数据集私有的分类树；默认样式键只引用本数据集快照。 */
@Data
public class GisVectorGroup {
    private Long id;
    private Long dataSetId;
    private Long datasetStyleId;
    private Long parentId;
    private String groupKey;
    private String groupName;
    private String nodeType;
    private String geometryType;
    private Boolean assignable;
    private String defaultStyleKey;
    private Boolean enabled;
    private Integer sortOrder;
    private Long version;
    private Long layerCount;
    private Long featureCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
