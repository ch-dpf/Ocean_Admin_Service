package org.ocean.admin.gis.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 一次固定源数据和策略版本的概化构建；attempt 是租约执行令牌。 */
@Data
public class GisVectorGeneralizationBuild {
    private Long id;
    private Long layerId;
    private Long dataVersion;
    private Long policyVersion;
    private String policyJson;
    private String buildStatus;
    private Long attempt;
    private Integer completedZoom;
    private Long maxTileBytes;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
