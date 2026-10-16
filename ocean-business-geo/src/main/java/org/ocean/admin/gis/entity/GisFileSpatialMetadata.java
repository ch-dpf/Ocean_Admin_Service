package org.ocean.admin.gis.entity;

import lombok.Data;

/** 文件中单个图层的空间元数据，几何以 WKT 在应用与 PostGIS 之间传递。 */
@Data
public class GisFileSpatialMetadata {
    private Long id;
    private Long fileMetaId;
    private String layerKey;
    private String layerName;
    private String dataKind;
    private String sourceCrsCode;
    private String sourceCrsWkt;
    /** 源 CRS 坐标轴单位，独立于统一米制分辨率。 */
    private String sourceUnit;
    private String sourceExtent;
    private String geographicExtent;
    private String extentKind;
    private Integer rasterWidth;
    private Integer rasterHeight;
    private Integer bandCount;
    /** EPSG:3857 投影下沿两个像元轴的米/像元间距；超出有效域时为空。 */
    private Double resolutionX;
    private Double resolutionY;
    /** 栅格分辨率统一为 m；矢量没有像元分辨率。 */
    private String resolutionUnit;
    private String tileMatrixSetId;
    private Integer tileWidth;
    private Integer tileHeight;
    private Integer recommendedMaxZoom;
    private String calculationDetails;
    private String extraMetadata;
    private Double west;
    private Double south;
    private Double east;
    private Double north;
}
