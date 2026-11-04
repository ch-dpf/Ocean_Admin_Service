package org.ocean.admin.gis.ogc;

import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationRecordService;
import org.ocean.admin.gis.service.PublicationService;
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
    private final PublicationService publicationService;
    private final PublicationRecordService recordService;

    public WmtsService(PublishedImageryCatalog catalog,
            PublicationService publicationService,
            PublicationRecordService recordService) {
        this.catalog = catalog;
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
            case "GETTILE" -> tileReply(resolveTile(
                    requireLayer(parameters.get("layer")),
                    parameters.get("style"),
                    parameters.get("tilematrixset"),
                    parameters.get("tilematrix"),
                    parameters.get("tilerow"),
                    parameters.get("tilecol"),
                    parameters.get("format")));
            default -> throw new OgcException("OperationNotSupported", "REQUEST",
                    "不支持的操作: " + operation);
        };
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
        return tileReply(resolveTile(requireLayer(layer), style, tileMatrixSet,
                tileMatrix, tileRow, tileCol, format));
    }

    /** 生成 GetCapabilities 文档；服务地址与既有发布接口同源。 */
    public String capabilitiesXml() {
        return WmtsXmlWriter.capabilities(
                recordService.buildPublicUrl("/gis/wmts"), catalog.listPublished());
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
        PublishedImageryCatalog.PublishedLayer published = catalog.load(layer.trim());
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
            Path file = publicationService.resolvePublishedFile(
                    GisProcessingType.IMAGERY, layer.serviceCode(), relativePath);
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
        return new WmtsReply(null, tile.file(), tile.mediaType());
    }

    private WmtsReply document(String xml) {
        return new WmtsReply(xml, null, null);
    }

    /**
     * WMTS 响应内容：能力文档或异常报告返回 {@code xml}，取图成功返回瓦片文件与媒体类型。
     */
    public record WmtsReply(String xml, Path file, String mediaType) {
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
