package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.service.PublicationRecordService;
import org.ocean.admin.gis.service.OgcServicePublicationService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** WMTS 1.0.0 协议语义：请求解析、参数校验、矩阵集一致性判断与瓦片文件定位。 */
@Service
public class WmtsService {

    private static final String DEFAULT_STYLE = "default";
    private static final String SERVICE_TYPE = "WMTS";
    private static final String VERSION = "1.0.0";

    private final PublishedImageryCatalog catalog;
    private final PublishedVectorCatalog vectorCatalog;
    private final VectorTileRenderer vectorTileRenderer;
    private final OgcServicePublicationService publicationService;
    private final PublicationRecordService recordService;

    public WmtsService(PublishedImageryCatalog catalog,
            PublishedVectorCatalog vectorCatalog,
            VectorTileRenderer vectorTileRenderer,
            OgcServicePublicationService publicationService,
            PublicationRecordService recordService) {
        this.catalog = catalog;
        this.vectorCatalog = vectorCatalog;
        this.vectorTileRenderer = vectorTileRenderer;
        this.publicationService = publicationService;
        this.recordService = recordService;
    }

    /** 处理 KVP 请求：GetCapabilities 或 GetTile。 */
    public WmtsReply handleKvp(Map<String, String> requestParameters) {
        Map<String, String> parameters = lowerCaseKeys(requestParameters);
        requireService(parameters);
        String operation = required(parameters.get("request"), "REQUEST");
        return switch (operation.toUpperCase(Locale.ROOT)) {
            case "GETCAPABILITIES" -> document(capabilitiesXml());
            case "GETTILE" -> getTile(parameters);
            default -> throw new OgcException("OperationNotSupported", "REQUEST",
                    "不支持的操作: " + operation);
        };
    }

    /** GetTile 先按矢量图层标识解析，未命中再走已生成影像瓦片文件。 */
    private WmtsReply getTile(Map<String, String> parameters) {
        String layer = required(parameters.get("layer"), "LAYER");
        PublishedVectorCatalog.VectorLayerView view = vectorView(layer,
                parameters.get("tilematrixset"));
        if (view == null) {
            return tileReply(resolveTile(
                    requireLayer(layer), parameters.get("style"),
                    parameters.get("tilematrixset"), parameters.get("tilematrix"),
                    parameters.get("tilerow"), parameters.get("tilecol"),
                    parameters.get("format")));
        }
        return vectorTile(view, parameters.get("style"),
                parameters.get("tilematrixset"), parameters.get("tilematrix"),
                parameters.get("tilerow"), parameters.get("tilecol"),
                parameters.get("format"), "WMTS");
    }

    /** KVP 的 LAYER 可用服务编码简写，具体矩阵集由 TILEMATRIXSET 决定；REST 可带 @矩阵集 后缀。 */
    private PublishedVectorCatalog.VectorLayerView vectorView(String layer,
            String matrixSetId) {
        if (matrixSetId != null && !matrixSetId.isBlank()) {
            String[] parts = layer.split("@", -1);
            if (parts.length == 2 && parts[1].equalsIgnoreCase(matrixSetId.trim())) {
                layer = parts[0];
            }
            return vectorCatalog.findView(layer, matrixSetId);
        }
        return vectorCatalog.findView(layer);
    }

    /** 校验矢量瓦片参数并交给动态光栅渲染器。 */
    private WmtsReply vectorTile(PublishedVectorCatalog.VectorLayerView view,
            String style, String tileMatrixSet, String tileMatrix,
            String tileRow, String tileCol, String format, String protocol) {
        String chosenStyle = style == null || style.isBlank()
                ? view.defaultStyleId() : style.trim();
        if (view.styles().stream().noneMatch(item -> item.id().equals(chosenStyle))) {
            throw new OgcException("InvalidParameterValue", "STYLE",
                    "图层 " + view.serviceCode() + " 不支持样式: " + chosenStyle);
        }
        if (tileMatrixSet == null || tileMatrixSet.isBlank()) {
            throw new OgcException("MissingParameterValue", "TILEMATRIXSET",
                    "缺少 TILEMATRIXSET 参数");
        }
        if (!view.matrixSet().id().equalsIgnoreCase(tileMatrixSet.trim())) {
            throw new OgcException("InvalidParameterValue", "TILEMATRIXSET",
                    "图层 " + view.serviceCode() + " 使用矩阵集 " + view.matrixSet().id());
        }
        if (format != null && !format.isBlank()
                && !view.mediaType().equalsIgnoreCase(format.trim())
                && !view.extension().equalsIgnoreCase(format.trim())) {
            throw new OgcException("InvalidParameterValue", "FORMAT",
                    "矢量瓦片仅支持 " + view.mediaType());
        }
        int zoom = parseInt(tileMatrix, "TILEMATRIX");
        int row = parseInt(tileRow, "TILEROW");
        int col = parseInt(tileCol, "TILECOL");
        if (zoom < view.minZoom() || zoom > view.maxZoom()) {
            throw new OgcException("TileOutOfRange", "TILEMATRIX",
                    "TILEMATRIX 超出图层层级范围 " + view.minZoom() + "-"
                            + view.maxZoom() + ": " + zoom);
        }
        if (row < 0 || row >= view.matrixSet().matrixHeight(zoom)
                || col < 0 || col >= view.matrixSet().matrixWidth(zoom)) {
            throw new OgcException("TileOutOfRange", "TILEROW/TILECOL",
                    "瓦片行列号超出矩阵范围: " + row + "/" + col);
        }
        try {
            return new WmtsReply(null, null, view.mediaType(), vectorTileRenderer.render(
                    view, chosenStyle, protocol, zoom, row, col));
        } catch (RuntimeException ex) {
            throw new OgcException("NoApplicableCode", null,
                    "矢量瓦片渲染失败: " + ex.getMessage());
        }
    }

    /**
     * 处理 RESTful 取图请求。
     *
     * @param tileColWithExtension 形如 {@code 200.png} 的列号与格式扩展名
     */
    public WmtsReply handleRestTile(String layer, String style, String tileMatrixSet,
            String tileMatrix, String tileRow, String tileColWithExtension) {
        int separator = tileColWithExtension == null ? -1 : tileColWithExtension.lastIndexOf('.');
        if (separator <= 0 || separator == tileColWithExtension.length() - 1) {
            throw new OgcException("InvalidParameterValue", "TILECOL",
                    "RESTful 瓦片地址缺少格式扩展名");
        }
        String tileCol = tileColWithExtension.substring(0, separator);
        String extension = tileColWithExtension.substring(separator + 1).toLowerCase(Locale.ROOT);
        String format = "jpg".equals(extension) || "jpeg".equals(extension)
                ? "image/jpeg" : "image/" + extension;
        String layerId = layer.contains("@") ? layer : layer + "@" + tileMatrixSet;
        PublishedVectorCatalog.VectorLayerView view = vectorCatalog.findView(layerId);
        if (view != null) {
            return vectorTile(view, style, tileMatrixSet, tileMatrix, tileRow, tileCol,
                    format, "WMTS");
        }
        return tileReply(resolveTile(
                requireLayer(layer.contains("@") ? layer.substring(0, layer.indexOf('@')) : layer),
                style, tileMatrixSet,
                tileMatrix, tileRow, tileCol, format));
    }

    /** 生成 GetCapabilities 文档；服务地址与既有发布接口同源。 */
    public String capabilitiesXml() {
        return WmtsXmlWriter.capabilities(
                recordService.buildPublicUrl("/gis/wmts"),
                catalog.listPublished(OgcProtocol.WMTS), vectorCatalog.listViews());
    }

    /** 生成 OGC ExceptionReport 文档。 */
    public String exceptionXml(OgcException exception) {
        return WmtsXmlWriter.exceptionReport(
                exception.code(), exception.locator(), exception.getMessage());
    }

    /** 解析图层；服务不存在、已停用或元数据非法时抛出 OGC 参数异常。 */
    public PublishedImageryCatalog.PublishedLayer requireLayer(String layer) {
        if (layer == null || layer.isBlank()) {
            throw new OgcException("MissingParameterValue", "LAYER", "缺少 LAYER 参数");
        }
        PublishedImageryCatalog.PublishedLayer published = catalog.load(layer.trim(), OgcProtocol.WMTS);
        if (published == null) {
            throw new OgcException("InvalidParameterValue", "LAYER", "图层不存在或已停用: " + layer);
        }
        return published;
    }

    /**
     * 定位 WMTS 请求对应的瓦片文件。
     *
     * <p>行列与层级均先按图层矩阵集校验，再换算为切片存储剖面下的行号。</p>
     */
    public TileResource resolveTile(PublishedImageryCatalog.PublishedLayer layer,
            String style, String tileMatrixSet, String tileMatrix,
            String tileRow, String tileCol, String format) {
        requireStyle(layer, style);
        requireMatrixSet(layer, tileMatrixSet);
        int zoom = parseInt(tileMatrix, "TILEMATRIX");
        int row = parseInt(tileRow, "TILEROW");
        int col = parseInt(tileCol, "TILECOL");
        String extension = requireFormat(layer, format);

        if (zoom < layer.minZoom() || zoom > layer.maxZoom()) {
            throw new OgcException("TileOutOfRange", "TILEMATRIX",
                    "TILEMATRIX 超出图层层级范围 " + layer.minZoom() + "-"
                            + layer.maxZoom() + ": " + zoom);
        }
        TileMatrixSetDefinition matrixSet = layer.matrixSet();
        if (row < 0 || row >= matrixSet.matrixHeight(zoom)) {
            throw new OgcException("TileOutOfRange", "TILEROW", "TILEROW 超出矩阵范围: " + row);
        }
        if (col < 0 || col >= matrixSet.matrixWidth(zoom)) {
            throw new OgcException("TileOutOfRange", "TILECOL", "TILECOL 超出矩阵范围: " + col);
        }
        int storageRow = matrixSet.storageRow(layer.tileProfile(), zoom, row);
        String relativePath = zoom + "/" + col + "/" + storageRow + "." + extension;
        try {
            Path file = publicationService.resolveTileFile(
                    layer.serviceCode(), OgcProtocol.WMTS, relativePath);
            return new TileResource(file, layer.mediaType());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 瓦片在矩阵内但图层范围未覆盖，按 OGC 约定返回 TileOutOfRange。
            throw new OgcException("TileOutOfRange", "TILEMATRIX",
                    "瓦片不存在或不在图层范围内: " + relativePath);
        }
    }

    private void requireService(Map<String, String> parameters) {
        String service = parameters.get("service");
        if (service != null && !service.isBlank()
                && !SERVICE_TYPE.equalsIgnoreCase(service.trim())) {
            throw new OgcException("InvalidParameterValue", "SERVICE", "SERVICE 仅支持 WMTS");
        }
        String version = parameters.get("version");
        if (version != null && !version.isBlank() && !VERSION.equals(version.trim())) {
            throw new OgcException("InvalidParameterValue", "VERSION", "VERSION 仅支持 1.0.0");
        }
    }

    /** KVP 参数名不区分大小写，统一按键名小写读取。 */
    private Map<String, String> lowerCaseKeys(Map<String, String> requestParameters) {
        Map<String, String> parameters = new HashMap<>();
        if (requestParameters != null) {
            requestParameters.forEach((key, value) -> {
                if (key != null && value != null) {
                    parameters.put(key.toLowerCase(Locale.ROOT), value);
                }
            });
        }
        return parameters;
    }

    private void requireStyle(PublishedImageryCatalog.PublishedLayer layer, String style) {
        String normalized = style == null || style.isBlank() ? DEFAULT_STYLE : style.trim();
        if (!DEFAULT_STYLE.equalsIgnoreCase(normalized)) {
            throw new OgcException("InvalidParameterValue", "STYLE",
                    "图层 " + layer.serviceCode() + " 仅支持 default 样式");
        }
    }

    private void requireMatrixSet(PublishedImageryCatalog.PublishedLayer layer, String tileMatrixSet) {
        if (tileMatrixSet == null || tileMatrixSet.isBlank()) {
            throw new OgcException("MissingParameterValue", "TILEMATRIXSET",
                    "缺少 TILEMATRIXSET 参数");
        }
        if (!layer.matrixSet().id().equalsIgnoreCase(tileMatrixSet.trim())) {
            throw new OgcException("InvalidParameterValue", "TILEMATRIXSET",
                    "图层 " + layer.serviceCode() + " 使用矩阵集 " + layer.matrixSet().id());
        }
    }

    private String requireFormat(PublishedImageryCatalog.PublishedLayer layer, String format) {
        if (format == null || format.isBlank()) {
            throw new OgcException("MissingParameterValue", "FORMAT", "缺少 FORMAT 参数");
        }
        if (!layer.mediaType().equalsIgnoreCase(format.trim())) {
            throw new OgcException("InvalidParameterValue", "FORMAT",
                    "图层 " + layer.serviceCode() + " 仅支持格式 " + layer.mediaType());
        }
        return layer.extension();
    }

    private int parseInt(String value, String locator) {
        if (value == null || value.isBlank()) {
            throw new OgcException("MissingParameterValue", locator, "缺少 " + locator + " 参数");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new OgcException("InvalidParameterValue", locator,
                    locator + " 必须为非负整数: " + value);
        }
        if (parsed < 0) {
            throw new OgcException("InvalidParameterValue", locator,
                    locator + " 必须为非负整数: " + value);
        }
        return parsed;
    }

    private String required(String value, String locator) {
        if (value == null || value.isBlank()) {
            throw new OgcException("MissingParameterValue", locator, "缺少 " + locator + " 参数");
        }
        return value.trim();
    }

    private WmtsReply tileReply(TileResource tile) {
        return new WmtsReply(null, tile.file(), tile.mediaType(), null);
    }

    private WmtsReply document(String xml) {
        return new WmtsReply(xml, null, null, null);
    }

    /**
     * WMTS 响应内容：能力文档或异常报告返回 {@code xml}，影像取图返回瓦片文件，
     * 矢量取图返回内存字节。
     */
    public record WmtsReply(String xml, Path file, String mediaType, byte[] body) {
    }

    /** 已定位的瓦片文件及其响应媒体类型。 */
    public record TileResource(Path file, String mediaType) {
    }

    /** WMTS 请求参数或资源定位失败，由控制层转换为 OGC ExceptionReport。 */
    public static class OgcException extends RuntimeException {

        private final String code;
        private final String locator;

        public OgcException(String code, String locator, String message) {
            super(message);
            this.code = code;
            this.locator = locator;
        }

        public String code() {
            return code;
        }

        public String locator() {
            return locator;
        }

        /** 服务内部故障返回 500，其余客户端错误返回 400。 */
        public boolean serverError() {
            return "NoApplicableCode".equals(code);
        }
    }
}
