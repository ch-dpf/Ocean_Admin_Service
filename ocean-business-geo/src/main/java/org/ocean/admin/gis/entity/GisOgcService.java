package org.ocean.admin.gis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.ocean.admin.gis.ogc.OgcProtocol;

import java.time.LocalDateTime;

/** WMTS、WMS、TMS 独立发布服务记录；不复用影像、地形或矢量发布记录。 */
@Data
@TableName(value = "gis_ogc_service", schema = "ocean_gis")
public class GisOgcService {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String serviceCode;
    private OgcProtocol protocol;
    private String serviceName;
    private String layerName;
    private String description;
    private Long tileSetId;
    private Long sourceTaskId;
    private Long dataSetId;
    private String status;
    private Integer minZoom;
    private Integer maxZoom;
    private String targetCrs;
    private String tileMatrixSet;
    private String outputFormat;
    private String tileProfile;
    private LocalDateTime publishTime;
    private LocalDateTime updateTime;
}
