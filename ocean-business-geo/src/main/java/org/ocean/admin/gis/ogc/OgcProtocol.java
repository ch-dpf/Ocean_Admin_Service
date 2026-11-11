package org.ocean.admin.gis.ogc;

import java.util.Locale;

/** 统一发布记录可声明的对外服务协议；WMS 运行时保留，但本版不再发布。 */
public enum OgcProtocol {

    WMS,
    WMTS,
    TMS;

    /** 发布向导可选择的协议；WMS 不在其中。 */
    public static boolean isPublishable(OgcProtocol protocol) {
        return protocol == WMTS || protocol == TMS;
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
