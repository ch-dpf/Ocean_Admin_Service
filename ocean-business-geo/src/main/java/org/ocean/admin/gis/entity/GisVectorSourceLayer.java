package org.ocean.admin.gis.entity;

import lombok.Data;

/** 导入时的原始要素集合，包括空集合和不能参与制图的记录。 */
@Data
public class GisVectorSourceLayer {
    private Long id;
    private Long resourceId;
    private String layerKey;
    private String layerName;
    private String tableName;
    private Long featureCount;
    private String sourceCrs;
    private String schemaJson;
}
