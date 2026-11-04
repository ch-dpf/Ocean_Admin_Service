package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationRecordService;
import org.ocean.admin.gis.service.PublicationService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Locale;

/**
 * TMS 1.0.0 协议语义：TileMap 标识解析、行列校验与瓦片文件定位。
 *
 * <p>与 WMTS 的关键差异在行号方向：TMS 的 {@code {y}} 自南向北递增，而切片产物按
 * {@code scheme} 记录为 XYZ（自北向南）或 TMS（自南向北）。这里先把 {@code {y}} 翻成
 * 自北向南的行号，再交给 {@link TileMatrixSetDefinition#storageRow} 换算存储行号，
 * 与 WMTS 的 GetTile 走同一条换算路径。</p>
 *
 * <p>TMS 1.0.0 没有定义异常报文，出错时由控制层直接返回 HTTP 状态码。</p>
 */
@Service
public class TmsService {

    /** 服务根地址；与其它 OGC 入口一致，由公开地址配置推导。 */
    private static final String BASE_PATH = "/gis/tms/1.0.0";
    private static final String SPEC_SEPARATOR = "@";

    private final PublishedImageryCatalog catalog;
    private final PublicationService publicationService;
    private final PublicationRecordService recordService;

    public TmsService(PublishedImageryCatalog catalog,
            PublicationService publicationService,
            PublicationRecordService recordService) {
        this.catalog = catalog;
        this.publicationService = publicationService;
        this.recordService = recordService;
    }

    /** 生成 TileMapService 根文档。 */
    public String tileMapServiceXml() {
        return TmsXmlWriter.tileMapService(baseUrl(), catalog.listPublished());
    }

    /** 生成单个 TileMap 文档；图层不存在时抛出 404。 */
    public String tileMapXml(String spec) {
        return TmsXmlWriter.tileMap(baseUrl(), requireLayer(spec));
    }

    /** 服务根地址，用于文档中的 TileMap 与 TileSet 链接。 */
    public String baseUrl() {
        return recordService.buildPublicUrl(BASE_PATH);
    }

    /**
     * 定位一个 TMS 瓦片请求对应的文件。
     *
     * @param spec TileMap 标识，形如 {@code IMG_xxx@WebMercatorQuad@png}
     * @param zoom 层级
     * @param column 列号，自西向东
     * @param row    行号，自南向北（TMS 约定）
     */
    public TileResource resolveTile(String spec, String zoom, String column, String row) {
        PublishedImageryCatalog.PublishedLayer layer = requireLayer(spec);
        int z = parseInt(zoom, "zoom");
        int x = parseInt(column, "x");
        int y = parseInt(row, "y");

        TileMatrixSetDefinition matrixSet = layer.matrixSet();
        if (z < layer.minZoom() || z > layer.maxZoom()) {
            throw new TmsException(404,
                    "层级 " + z + " 超出图层范围 " + layer.minZoom() + "-" + layer.maxZoom());
        }
        if (x < 0 || x >= matrixSet.matrixWidth(z) || y < 0 || y >= matrixSet.matrixHeight(z)) {
            throw new TmsException(404, "瓦片行列号超出矩阵范围: " + x + "/" + y);
        }

        int topDownRow = matrixSet.matrixHeight(z) - 1 - y;
        int storageRow = matrixSet.storageRow(layer.tileProfile(), z, topDownRow);
        String relativePath = z + "/" + x + "/" + storageRow + "." + layer.extension();
        try {
            Path file = publicationService.resolvePublishedFile(
                    GisProcessingType.IMAGERY, layer.serviceCode(), relativePath);
            return new TileResource(file, layer.mediaType());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 行列号在矩阵内但图层范围未覆盖，该瓦片不存在
            throw new TmsException(404, "瓦片不存在: " + relativePath);
        }
    }

    /**
     * 解析 TileMap 标识并加载图层。
     *
     * @param spec 形如 {@code 服务编码@矩阵集@格式}
     */
    private PublishedImageryCatalog.PublishedLayer requireLayer(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new TmsException(400, "缺少 TileMap 标识");
        }
        String[] parts = spec.trim().split(SPEC_SEPARATOR, -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new TmsException(400, "TileMap 标识应为 服务编码@矩阵集@格式: " + spec);
        }
        PublishedImageryCatalog.PublishedLayer layer = catalog.load(parts[0].trim());
        if (layer == null) {
            throw new TmsException(404, "图层不存在或已停用: " + parts[0]);
        }
        if (!layer.matrixSet().id().equalsIgnoreCase(parts[1].trim())) {
            throw new TmsException(404,
                    "图层 " + layer.serviceCode() + " 使用矩阵集 " + layer.matrixSet().id());
        }
        if (!layer.extension().equalsIgnoreCase(parts[2].trim())) {
            throw new TmsException(404,
                    "图层 " + layer.serviceCode() + " 仅提供 " + layer.extension() + " 格式");
        }
        return layer;
    }

    private int parseInt(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TmsException(400, "缺少 " + name + " 参数");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            throw new TmsException(400, name + " 必须为非负整数: " + value);
        }
    }

    /** 已定位的瓦片文件及其响应媒体类型。 */
    public record TileResource(Path file, String mediaType) {
    }

    /**
     * TMS 请求失败。
     *
     * <p>TMS 1.0.0 未定义异常文档格式，因此只携带 HTTP 状态码与说明文本。</p>
     */
    public static class TmsException extends RuntimeException {

        private final int status;

        public TmsException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
