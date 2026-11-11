package org.ocean.admin.gis.ogc;

import java.util.Locale;

/** 单协议发布记录的对外服务协议；任务发布 WMTS/TMS，数据集独立发布 WMS。 */
public enum OgcProtocol {

    WMS,
    WMTS,
    TMS,
    /** Cesium 量化地形专用协议；量化网格没有对应的 OGC 瓦片标准，不属于 OGC 协议。 */
    TERRAIN;

    /** 任务发布向导可选择的协议；数据集 WMS 使用独立入口。 */
    public static boolean isPublishable(OgcProtocol protocol) {
        return protocol == WMTS || protocol == TMS || protocol == TERRAIN;
    }

    /** 解析请求中的协议名称；仅接受本版可发布的协议。 */
    public static OgcProtocol parsePublishable(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("服务协议不能为空");
        }
        OgcProtocol protocol;
        try {
            protocol = valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的服务协议: " + value, ex);
        }
        if (!isPublishable(protocol)) {
            throw new IllegalArgumentException("不支持的服务协议: " + protocol);
        }
        return protocol;
    }

    /** 按数据库中的单协议字段解析；未知值返回 null。 */
    public static OgcProtocol fromStored(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
