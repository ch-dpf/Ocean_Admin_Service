package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 应用与 PostGIS 之间通过 GeoJSON 几何片段及 JSON 属性传递要素。 */
@Data
public class GisVectorFeature {
    private Long id;
    private Long layerId;
    private String geometryJson;
    private String propertiesJson;
    private String sourceFeatureId;
    private Long sourceFeatureIndex;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
