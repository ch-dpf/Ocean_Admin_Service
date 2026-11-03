package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.time.LocalDateTime;

/** 地形、影像和矢量瓦片服务共用的发布记录视图。 */
@Data
@Builder
public class GisPublicationVO {
    private Long id;
    private String serviceCode;
    private String serviceName;

    @Schema(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
    private GisProcessingType processingType;

    private Long tileSetId;
    private Long sourceTaskId;

    @Schema(description = "通过任务 ID 查询的当前任务名称，不保存名称快照")
    private String taskName;

    private Long dataSetId;

    @Schema(description = "通过数据集 ID 查询的当前名称；未关联或数据集已删除时为空")
    private String dataSetName;

    @Schema(description = "发布状态：PUBLISHED、DISABLED")
    private String status;

    private String outputKey;
    private String targetCrs;
    private String tileProfile;
    private String outputFormat;
    private Integer minZoom;
    private Integer maxZoom;

    @Schema(description = "服务访问地址：地形为以 / 结尾的根地址，影像为包含 {z}/{x}/{y} 和格式后缀的瓦片模板")
    private String serviceUrl;

    @Schema(description = "服务描述文件地址，影像为 tilejson.json，地形为 layer.json")
    private String descriptorUrl;

    @Schema(description = "瓦片访问模板；不适用的发布类型为空")
    private String tileUrlTemplate;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
