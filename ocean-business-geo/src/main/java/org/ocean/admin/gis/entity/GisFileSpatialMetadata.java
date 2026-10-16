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
    private String sourceUnit;
    private String sourceExtent;
    private String geographicExtent;
    private String extentKind;
    private Integer rasterWidth;
    private Integer rasterHeight;
    private Integer bandCount;
    private Double resolutionX;
    private Double resolutionY;
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
