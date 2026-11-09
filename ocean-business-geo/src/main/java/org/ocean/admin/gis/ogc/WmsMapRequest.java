package org.ocean.admin.gis.ogc;

import java.awt.Color;
import java.util.List;

/**
 * 结构化的 WMS 1.3.0 / 1.1.1 GetMap 请求参数。
 *
 * @param layers      请求图层列表
 * @param styles      样式列表，通常与 layers 一一对应或为空
 * @param crs         目标坐标系标识，如 EPSG:3857 或 EPSG:4326（WMS 1.1.1 为 SRS）
 * @param bbox        范围数组：[minX, minY, maxX, maxY]，顺序已按 CRS 投影坐标规整为 (x, y)
 * @param width       输出图像像素宽度
 * @param height      输出图像像素高度
 * @param format      输出媒体类型，如 image/png、image/jpeg
 * @param transparent 是否启用透明通道（仅 PNG 有效）
 * @param bgcolor     背景色，默认白色；transparent=true 时为透明色
 */
public record WmsMapRequest(
        List<String> layers,
        List<String> styles,
        String crs,
        double[] bbox,
        int width,
        int height,
        String format,
        boolean transparent,
        Color bgcolor) {

    public static final int MAX_DIMENSION = 4096;
    public static final int MIN_DIMENSION = 1;

    public WmsMapRequest {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("LAYERS 不能为空");
        }
        if (bbox == null || bbox.length != 4) {
            throw new IllegalArgumentException("BBOX 必须包含4个坐标值");
        }
        if (bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) {
            throw new IllegalArgumentException("BBOX 范围无效: min 必须小于 max");
        }
        if (width < MIN_DIMENSION || width > MAX_DIMENSION
                || height < MIN_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("WIDTH 与 HEIGHT 必须在 1 到 "
                    + MAX_DIMENSION + " 之间");
        }
        if (crs == null || crs.isBlank()) {
            throw new IllegalArgumentException("CRS/SRS 不能为空");
        }
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("FORMAT 不能为空");
        }
    }
}
