package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.ogc.PublicationLayer.VectorLayerView;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 矢量 WMTS/TMS 的动态瓦片缓存。
 *
 * <p>缓存键包含服务编码、样式发布版本、矩阵集、层级、行列与样式方案，样式更新或
 * 服务停用会自然换键；容量按字节控制，避免按需出图重复读取 GPKG 和重复绘制。</p>
 */
@Service
public class VectorTileCache {

    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;

    private final Map<String, byte[]> tiles = new LinkedHashMap<>(256, 0.75f, true);
    private final AtomicLong bytes = new AtomicLong();

    /** 命中返回缓存字节，未命中返回 null；访问顺序用于 LRU 淘汰。 */
    public byte[] get(String key) {
        synchronized (tiles) {
            byte[] value = tiles.get(key);
            return value == null ? null : value.clone();
        }
    }

    /** 写入缓存；超出容量时按最近最少使用顺序淘汰。 */
    public void put(String key, byte[] body) {
        synchronized (tiles) {
            byte[] previous = tiles.put(key, body.clone());
            bytes.addAndGet(body.length - (previous == null ? 0 : previous.length));
            var iterator = tiles.entrySet().iterator();
            while ((bytes.get() > MAX_BYTES || tiles.size() > MAX_ENTRIES)
                    && iterator.hasNext()) {
                Map.Entry<String, byte[]> entry = iterator.next();
                bytes.addAndGet(-entry.getValue().length);
                iterator.remove();
            }
        }
    }

    /** 缓存键；坐标、样式和发布版本任一变化都不会复用旧瓦片。 */
    public String key(VectorLayerView view, String styleId, String protocol,
            int zoom, int row, int column) {
        return String.join("|", protocol, view.serviceCode(), view.releaseVersion(),
                view.matrixSet().id(), styleId, Integer.toString(zoom),
                Integer.toString(row), Integer.toString(column));
    }

    /** 生成透明画布；动态矢量瓦片固定为 256×256 PNG。 */
    public BufferedImage canvas(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    /** 瓦片内容的 ETag：内容摘要加长度，避免不同长度的同摘要碰撞。 */
    public String etag(byte[] body) {
        return "\"" + Integer.toHexString(Arrays.hashCode(body))
                + "-" + Integer.toHexString(body.length) + "\"";
    }
}
