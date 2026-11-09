package org.ocean.admin.gis.ogc;

import java.util.Locale;

/** OGC 服务协议类型；一条独立发布记录只对应一种协议。 */
public enum OgcProtocol {

    WMTS,
    WMS,
    TMS;

    /** 解析并校验请求中的协议名称。 */
    public static OgcProtocol parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("服务类型不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("服务类型只能为 WMTS、WMS 或 TMS", ex);
        }
    }
}
