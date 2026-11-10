package org.ocean.admin.gis.ogc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 统一发布记录可声明的对外服务协议；本版不提供 WFS 与 XYZ。 */
public enum OgcProtocol {

    WMS,
    WMTS,
    TMS;

    public static final Set<OgcProtocol> IMAGERY = Set.of(WMS, WMTS, TMS);
    public static final Set<OgcProtocol> VECTOR = Set.of(WMS, WMTS, TMS);

    /** 解析并校验请求中的协议名称。 */
    public static OgcProtocol parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("服务协议不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的服务协议: " + value, ex);
        }
    }

    /** 按原始数据类别校验协议集合，返回去重的协议集合。 */
    public static Set<OgcProtocol> validate(String processingType, List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("至少需要选择一个服务协议");
        }
        Set<OgcProtocol> allowed = "VECTOR".equalsIgnoreCase(processingType)
                ? VECTOR : IMAGERY;
        Set<OgcProtocol> protocols = new LinkedHashSet<>();
        for (String value : values) {
            OgcProtocol protocol = parse(value);
            if (!allowed.contains(protocol)) {
                throw new IllegalArgumentException(
                        processingType + " 不支持服务协议: " + protocol);
            }
            protocols.add(protocol);
        }
        return protocols;
    }
}
