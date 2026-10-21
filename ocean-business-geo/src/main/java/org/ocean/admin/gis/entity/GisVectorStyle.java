package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 图层样式方案；JSON 定义及编辑版本独立于要素数据。 */
@Data
public class GisVectorStyle {
    private Long id;
    private Long layerId;
    private String name;
    private Boolean isDefault;
    private String definitionJson;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
