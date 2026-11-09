package org.ocean.admin.gis.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.ocean.admin.gis.ogc.OgcProtocol;

import java.time.LocalDateTime;
import java.util.List;

/** WMTS、WMS、TMS 独立发布服务的列表与详情视图。 */
@Data
@Builder
public class GisOgcServiceVO {

    private Long id;
    private String serviceCode;

    @Schema(description = "服务类型：WMTS、WMS、TMS")
    private OgcProtocol protocol;

    private String serviceName;
    private String layerName;
    private String description;

    @Schema(description = "发布状态：PUBLISHED、DISABLED")
    private String status;

    @Schema(description = "产物可用性：AVAILABLE、TILE_SET_NOT_READY、DESCRIPTOR_MISSING、TILES_MISSING、METADATA_INVALID")
    private String availability;

    private Long sourceTaskId;
    private String taskName;
    private Long tileSetId;
    private Long dataSetId;
    private String dataSetName;

    private Integer minZoom;
    private Integer maxZoom;
    private String targetCrs;
    private String tileMatrixSet;
    private String outputFormat;
    private String tileProfile;

    @Schema(description = "服务根地址")
    private String serviceUrl;

    @Schema(description = "能力文档地址")
    private String capabilitiesUrl;

    @Schema(description = "瓦片访问模板；WMS 不适用时为空")
    private String tileUrlTemplate;

    @Schema(description = "WMTS 各层级有效行列范围；仅 WMTS 返回")
    private List<WmtsMatrixLimitsVO> wmtsTileMatrixSetLimits;

    private LocalDateTime publishTime;
    private LocalDateTime updateTime;

    /** 单层级 WMTS 瓦片行列范围。 */
    @Data
    @Builder
    public static class WmtsMatrixLimitsVO {
        private Integer zoom;
        private Integer minRow;
        private Integer maxRow;
        private Integer minCol;
        private Integer maxCol;
    }
}
