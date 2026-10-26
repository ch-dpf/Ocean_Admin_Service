package org.ocean.admin.gis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/** 文件列表查询条件；经纬度顺序为 X=经度、Y=纬度。 */
@Data
public class GisFileQuery {
    private Integer current = 1;
    private Integer size = 10;
    private Long dataSetId;
    private Long categoryId;
    private String originalName;
    private String extension;
    private String uploadStatus;
    private String metadataStatus;
    private Double west;
    private Double south;
    private Double east;
    private Double north;
    @Schema(description = "INTERSECTS：相交；COVERED_BY：完全位于框内（含边界）")
    private String spatialRelation = "INTERSECTS";
    private Integer requiredZoom;
    private String tileMatrixSetId = "WebMercatorQuad";
    @Schema(description = "是否返回完整范围几何，默认仅返回范围摘要")
    private boolean includeGeometry;

    public boolean hasBounds() {
        return west != null && south != null && east != null && north != null;
    }

    public void validate() {
        current = current == null || current < 1 ? 1 : current;
        size = size == null || size < 1 ? 10 : Math.min(size, 100);
        originalName = trim(originalName);
        extension = trim(extension);
        if (extension != null) extension = extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        uploadStatus = upper(uploadStatus);
        metadataStatus = upper(metadataStatus);
        spatialRelation = upper(spatialRelation);
        if (spatialRelation == null) spatialRelation = "INTERSECTS";
        if (!Set.of("INTERSECTS", "COVERED_BY").contains(spatialRelation)) {
            throw new IllegalArgumentException("空间关系只能为 INTERSECTS 或 COVERED_BY");
        }
        if (uploadStatus != null && !Set.of("PENDING", "READY", "FAILED").contains(uploadStatus)) {
            throw new IllegalArgumentException("上传状态只能为 PENDING、READY 或 FAILED");
        }
        if (metadataStatus != null && !Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "NOT_APPLICABLE").contains(metadataStatus)) {
            throw new IllegalArgumentException("元数据状态无效");
        }
        boolean any = west != null || south != null || east != null || north != null;
        if (any && !hasBounds()) throw new IllegalArgumentException("框选四个坐标必须同时提供");
        if (hasBounds() && (!Double.isFinite(west) || !Double.isFinite(east)
                || !Double.isFinite(south) || !Double.isFinite(north)
                || west < -180 || west > 180 || east < -180 || east > 180
                || south < -90 || north > 90 || south >= north || west.doubleValue() == east.doubleValue()
                || (west == 180 && east == -180))) {
            throw new IllegalArgumentException("框选经纬度范围无效");
        }
        if (requiredZoom != null && (requiredZoom < 0 || requiredZoom > 22)) {
            throw new IllegalArgumentException("要求层级必须在0到22之间");
        }
        if (tileMatrixSetId == null || !Set.of("WebMercatorQuad", "WorldCRS84Quad").contains(tileMatrixSetId)) {
            throw new IllegalArgumentException("瓦片矩阵仅支持 WebMercatorQuad 或 WorldCRS84Quad");
        }
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
