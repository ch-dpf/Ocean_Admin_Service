package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 完整的数据集样式方案，发布时复制规则，不引用可变编辑状态。 */
@Data
public class GisVectorStyleScheme {
    private Long id;
    private Long dataSetId;
    private String name;
    private Long version;
    private String definitionJson;
    private LocalDateTime updateTime;
}
