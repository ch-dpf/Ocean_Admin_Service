package org.ocean.admin.gis.ogc;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 量化地形（quantized-mesh）服务语义：layer.json 元数据直出与 .terrain 瓦片定位。
 *
 * <p>量化网格没有对应的 OGC 瓦片标准，客户端（CesiumTerrainProvider）约定在服务根
 * 读取 layer.json，再按 {@code {z}/{x}/{y}.terrain} 取瓦片。mago 切片产物本身就是
 * 该布局，因此 layer.json 原样透传、不做二次生成，完整保留 bounds、available 与
 * 扩展声明；瓦片行号自南向北递增（TMS 约定），走
 * {@link TileMatrixSetDefinition#storageRow} 与其它 OGC 协议相同的换算路径。</p>
 *
 * <p>量化地形没有标准异常报文，出错时由控制层直接返回 HTTP 状态码。</p>
 */
@Service
public class TerrainService {

    private final PublicationCatalog catalog;

    public TerrainService(PublicationCatalog catalog) {
        this.catalog = catalog;
    }

    /** 定位切片产物自带的 layer.json；元数据缺失视为服务不可用。 */
    public Path resolveLayerJson(String serviceCode) {
        PublicationLayer.RasterLayer layer = requireLayer(serviceCode);
        try {
            return catalog.resolveTileFile(layer.serviceCode(), OgcProtocol.TERRAIN, "layer.json");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 发布记录有效但产物目录中没有 layer.json
            throw new TerrainException(404, "地形元数据不存在: " + ex.getMessage());
        }
    }

    /**
     * 定位一个量化地形瓦片请求对应的文件。
     *
     * @param zoom   层级
     * @param column 列号，自西向东
     * @param row    行号，自南向北（CesiumTerrainProvider 约定）
     */
    public TileResource resolveTile(String serviceCode, String zoom, String column, String row) {
        PublicationLayer.RasterLayer layer = requireLayer(serviceCode);
        int z = parseInt(zoom, "z");
        int x = parseInt(column, "x");
        int y = parseInt(row, "y");

        TileMatrixSetDefinition matrixSet = layer.matrixSet();
        if (z < layer.minZoom() || z > layer.maxZoom()) {
            throw new TerrainException(404,
                    "层级 " + z + " 超出图层范围 " + layer.minZoom() + "-" + layer.maxZoom());
        }
        if (x < 0 || x >= matrixSet.matrixWidth(z) || y < 0 || y >= matrixSet.matrixHeight(z)) {
            throw new TerrainException(404, "瓦片行列号超出矩阵范围: " + x + "/" + y);
        }

        int topDownRow = matrixSet.matrixHeight(z) - 1 - y;
        int storageRow = matrixSet.storageRow(layer.tileProfile(), z, topDownRow);
        String relativePath = z + "/" + x + "/" + storageRow + "." + layer.extension();
        try {
            Path file = catalog.resolveTileFile(
                    layer.serviceCode(), OgcProtocol.TERRAIN, relativePath);
            return new TileResource(file, layer.mediaType());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 行列号在矩阵内但该瓦片未生成（地形金字塔中允许存在空洞）
            throw new TerrainException(404, "瓦片不存在: " + relativePath);
        }
    }

    /** 按服务编码加载 TERRAIN 协议的已发布静态图层。 */
    private PublicationLayer.RasterLayer requireLayer(String serviceCode) {
        if (serviceCode == null || serviceCode.isBlank()) {
            throw new TerrainException(400, "缺少服务编码");
        }
        String code = serviceCode.trim();
        PublicationLayer.RasterLayer layer = catalog.loadRaster(code, OgcProtocol.TERRAIN);
        if (layer == null) {
            throw new TerrainException(404, "图层不存在或已停用: " + code);
        }
        return layer;
    }

    private int parseInt(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TerrainException(400, "缺少 " + name + " 参数");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            throw new TerrainException(400, name + " 必须为非负整数: " + value);
        }
    }

    /** 已定位的量化地形文件。 */
    public record TileResource(Path file, String mediaType) {
    }

    /**
     * 量化地形请求失败。
     *
     * <p>该协议没有标准异常文档格式，因此只携带 HTTP 状态码与说明文本。</p>
     */
    public static class TerrainException extends RuntimeException {

        private final int status;

        public TerrainException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
