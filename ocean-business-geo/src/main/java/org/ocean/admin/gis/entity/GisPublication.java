package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/** 地形、影像和矢量服务共用的发布记录。 */
@Data
@TableName(value = "gis_publication", schema = "ocean_gis")
public class GisPublication {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String serviceCode;
    private String serviceName;
    private String serviceProtocol;
    @TableField("protocols")
    private String protocolsJson;
    private Long sourceTaskId;
    private String processingType;
    private String layerName;
    private String title;
    @TableField("abstract")
    private String abstractText;
    private String keywords;
    private String defaultCrs;
    @TableField("supported_crs")
    private String supportedCrsJson;
    @TableField("tile_matrix_sets")
    private String tileMatrixSetsJson;
    private Integer minZoom;
    private Integer maxZoom;
    private String targetCrs;
    private String tileMatrixSet;
    private String outputFormat;
    private String tileProfile;
    @TableField("bounds_wgs84")
    private String boundsWgs84Json;
    private String styleId;
    private String styleName;
    private String publicBaseUrl;
    @TableField("capabilities_urls")
    private String capabilitiesUrlsJson;
    @TableField("service_urls")
    private String serviceUrlsJson;
    @TableField("tile_url_templates")
    private String tileUrlTemplatesJson;
    private Long tileSetId;
    private Long vectorResourceId;
    private String vectorDefinition;
    private Long dataSetId;
    private String status;
    private LocalDateTime publishTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
