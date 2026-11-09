package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.ogc.PublishedVectorCatalog.VectorLayerView;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.Semaphore;

/**
 * 按标准瓦片矩阵集把已发布矢量服务动态渲染成 PNG，供 WMTS 与 TMS 共用。
 *
 * <p>瓦片不落盘，按服务、样式发布版本和矩阵坐标缓存在内存中。TMS 的南向北行号
 * 由调用方先换成 WMTS 的自北向南行号，本服务只处理统一的行列语义。</p>
 */
@Service
public class VectorTileRenderer {

    /** 并发渲染上限，避免大量瓦片请求同时启动 GDAL 工作进程。 */
    private static final Semaphore SLOTS = new Semaphore(4, true);

    private final PublishedVectorCatalog catalog;
    private final VectorMapRenderer mapRenderer;
    private final VectorTileCache cache;

    public VectorTileRenderer(PublishedVectorCatalog catalog,
            VectorMapRenderer mapRenderer, VectorTileCache cache) {
        this.catalog = catalog;
        this.mapRenderer = mapRenderer;
        this.cache = cache;
    }

    /**
     * 渲染一块瓦片。
     *
     * @param view     矢量瓦片图层描述
     * @param styleId  样式方案 ID，为空时使用默认样式
     * @param protocol 协议名，参与缓存键隔离
     * @param zoom     层级
     * @param row      自北向南的行号
     * @param column   自西向东的列号
     */
    public byte[] render(VectorLayerView view, String styleId, String protocol,
            int zoom, int row, int column) {
        String chosenStyle = styleId == null || styleId.isBlank()
                ? view.defaultStyleId() : styleId.trim();
        String key = cache.key(view, chosenStyle, protocol, zoom, row, column);
        byte[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean acquired;
        try {
            SLOTS.acquire();
            acquired = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("矢量瓦片渲染等待已中断", ex);
        }
        try {
            cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            PublishedVectorCatalog.PublishedVectorLayer layer =
                    catalog.load(view.serviceCode(), chosenStyle);
            if (layer == null) {
                throw new IllegalArgumentException("图层或样式不存在或已停用: " + view.serviceCode());
            }
            TileMatrixSetDefinition matrixSet = view.matrixSet();
            double tileSpan = matrixSet.unitsPerPixel(zoom) * TileMatrixSetDefinition.TILE_SIZE;
            double minX = matrixSet.topLeftX() + column * tileSpan;
            double maxX = minX + tileSpan;
            double maxY = matrixSet.topLeftY() - row * tileSpan;
            double minY = maxY - tileSpan;
            String crs = matrixSet == TileMatrixSetDefinition.WEB_MERCATOR_QUAD
                    ? "EPSG:3857" : "EPSG:4326";
            BufferedImage image = cache.canvas(TileMatrixSetDefinition.TILE_SIZE,
                    TileMatrixSetDefinition.TILE_SIZE);
            Graphics2D graphics = image.createGraphics();
            try {
                mapRenderer.draw(graphics, new VectorMapRenderer.RenderWindow(
                        new double[]{minX, minY, maxX, maxY}, crs,
                        TileMatrixSetDefinition.TILE_SIZE,
                        TileMatrixSetDefinition.TILE_SIZE, zoom), layer, chosenStyle);
            } finally {
                graphics.dispose();
            }
            byte[] body = VectorMapRenderer.png(image);
            image.flush();
            cache.put(key, body);
            return body;
        } finally {
            if (acquired) {
                SLOTS.release();
            }
        }
    }
}
