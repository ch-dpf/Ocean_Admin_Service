package org.ocean.admin.gis.ogc;

import java.util.Locale;

/**
 * 标准瓦片矩阵集定义，供 WMTS 1.0.0 与 TMS 1.0.0 共用。
 *
 * <p>仅收录切片阶段已经支持的目标坐标系，矩阵尺寸与切片链路保持一致：
 * WebMercatorQuad 零级 1x1，WorldCRS84Quad 零级 2x1。</p>
 */
public enum TileMatrixSetDefinition {

    WEB_MERCATOR_QUAD("WebMercatorQuad", "urn:ogc:def:crs:EPSG::3857",
            "urn:ogc:def:wkss:OGC:1.0:GoogleMapsCompatible",
            -20037508.342789244, 20037508.342789244, 1,
            559082264.0287178),

    WORLD_CRS84_QUAD("WorldCRS84Quad", "urn:ogc:def:crs:OGC:1.3:CRS84",
            "urn:ogc:def:wkss:OGC:1.0:GoogleCRS84Quad",
            -180d, 90d, 2,
            279541132.0143589);

    /** WMTS 标准瓦片像素尺寸。 */
    public static final int TILE_SIZE = 256;
    public static final int MAX_ZOOM = 22;

    private final String id;
    private final String supportedCrs;
    private final String wellKnownScaleSet;
    private final double topLeftX;
    private final double topLeftY;
    private final int levelZeroWidth;
    private final double levelZeroScaleDenominator;

    TileMatrixSetDefinition(String id, String supportedCrs, String wellKnownScaleSet,
            double topLeftX, double topLeftY, int levelZeroWidth,
            double levelZeroScaleDenominator) {
        this.id = id;
        this.supportedCrs = supportedCrs;
        this.wellKnownScaleSet = wellKnownScaleSet;
        this.topLeftX = topLeftX;
        this.topLeftY = topLeftY;
        this.levelZeroWidth = levelZeroWidth;
        this.levelZeroScaleDenominator = levelZeroScaleDenominator;
    }

    public String id() {
        return id;
    }

    public String supportedCrs() {
        return supportedCrs;
    }

    public String wellKnownScaleSet() {
        return wellKnownScaleSet;
    }

    public double topLeftX() {
        return topLeftX;
    }

    public double topLeftY() {
        return topLeftY;
    }

    public int matrixWidth(int zoom) {
        return levelZeroWidth << zoom;
    }

    public int matrixHeight(int zoom) {
        return 1 << zoom;
    }

    public double scaleDenominator(int zoom) {
        return levelZeroScaleDenominator / Math.pow(2, zoom);
    }

    /** WMTS 的 GetTile 使用从北向南递增的行号，存储剖面为 XYZ 时与行号一致。 */
    public int storageRow(String tileProfile, int zoom, int row) {
        return "TMS".equalsIgnoreCase(tileProfile) ? matrixHeight(zoom) - 1 - row : row;
    }

    /** 按 WMTS 矩阵集标识解析；未知标识返回 null，由调用方转换为 OGC 异常。 */
    public static TileMatrixSetDefinition resolve(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (TileMatrixSetDefinition definition : values()) {
            if (definition.id.toUpperCase(Locale.ROOT).equals(normalized)) {
                return definition;
            }
        }
        return null;
    }

    /**
     * 按切片目标坐标系解析矩阵集，供发布接口向前端返回 WMTS 参数。
     *
     * @param crs 形如 {@code EPSG:3857} 的坐标系编码
     * @return 匹配的矩阵集；空白或暂不支持的坐标系返回 null
     */
    public static TileMatrixSetDefinition resolveByCrs(String crs) {
        if (crs == null || crs.isBlank()) {
            return null;
        }
        return switch (crs.trim().toUpperCase(Locale.ROOT)) {
            case "EPSG:3857" -> WEB_MERCATOR_QUAD;
            case "EPSG:4326" -> WORLD_CRS84_QUAD;
            default -> null;
        };
    }

    /**
     * 将 WGS84 地理范围换算到本矩阵集下的瓦片行列范围，用于能力文档中的 TileMatrixSetLimits。
     *
     * @param zoom      层级
     * @param wgs84     地理范围，顺序为 minLon、minLat、maxLon、maxLat
     */
    public MatrixLimits limits(int zoom, double[] wgs84) {
        double minX = projectX(wgs84[0]);
        double maxX = projectX(wgs84[2]);
        double minY = projectY(wgs84[1]);
        double maxY = projectY(wgs84[3]);
        double tileSpan = pixelSize(zoom) * TILE_SIZE;
        int maxCol = matrixWidth(zoom) - 1;
        int maxRow = matrixHeight(zoom) - 1;
        int minCol = clamp((int) Math.floor((minX - topLeftX) / tileSpan), 0, maxCol);
        int minRow = clamp((int) Math.floor((topLeftY - maxY) / tileSpan), 0, maxRow);
        // 范围退化或跨日界线时，保证闭区间顺序合法且至少覆盖一个瓦片。
        int maxColIndex = Math.max(minCol,
                clamp((int) Math.floor((maxX - topLeftX) / tileSpan), 0, maxCol));
        int maxRowIndex = Math.max(minRow,
                clamp((int) Math.floor((topLeftY - minY) / tileSpan), 0, maxRow));
        return new MatrixLimits(minCol, maxColIndex, minRow, maxRowIndex);
    }

    private double pixelSize(int zoom) {
        double worldWidth = this == WEB_MERCATOR_QUAD ? 2 * 20037508.342789244 : 360d;
        return worldWidth / ((double) matrixWidth(zoom) * TILE_SIZE);
    }

    /** TMS 1.0.0 的 profile 标识，与切片矩阵集一一对应。 */
    public String tmsProfile() {
        return this == WEB_MERCATOR_QUAD ? "global-mercator" : "global-geodetic";
    }

    /**
     * TMS 1.0.0 的 SRS 标识。
     *
     * <p>WorldCRS84Quad 对外声明 EPSG:4326：TMS 恒为 x=经度、y=纬度，
     * 不存在 WMTS/WMS 里 EPSG:4326 轴序颠倒的问题。</p>
     */
    public String tmsSrs() {
        return this == WEB_MERCATOR_QUAD ? "EPSG:3857" : "EPSG:4326";
    }

    /** 该层级的瓦片分辨率（SRS 单位/像素），对应 TileSet 的 units-per-pixel。 */
    public double unitsPerPixel(int zoom) {
        return pixelSize(zoom);
    }

    /** 矩阵原点左下角横坐标；TMS 的行号自南向北递增，故原点是矩阵的左下角。 */
    public double originX() {
        return topLeftX;
    }

    /** 矩阵原点左下角纵坐标。 */
    public double originY() {
        return this == WEB_MERCATOR_QUAD ? -20037508.342789244 : -90d;
    }

    /**
     * 把 WGS84 地理范围换算成本矩阵集坐标系下的范围，用于 TileMap 的 BoundingBox。
     *
     * @param wgs84 地理范围，顺序为 minLon、minLat、maxLon、maxLat
     * @return 顺序为 minX、minY、maxX、maxY
     */
    public double[] targetBounds(double[] wgs84) {
        return new double[]{
                projectX(wgs84[0]), projectY(wgs84[1]),
                projectX(wgs84[2]), projectY(wgs84[3])};
    }

    private double projectX(double longitude) {
        return this == WEB_MERCATOR_QUAD
                ? longitude * 20037508.342789244 / 180d : longitude;
    }

    private double projectY(double latitude) {
        if (this != WEB_MERCATOR_QUAD) {
            return latitude;
        }
        double clamped = Math.max(-85.05112877980659, Math.min(85.05112877980659, latitude));
        double radians = Math.toRadians(clamped);
        return Math.log(Math.tan(Math.PI / 4d + radians / 2d)) * 20037508.342789244 / Math.PI;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 某一层级下图层实际覆盖的瓦片行列范围，闭区间。 */
    public record MatrixLimits(int minCol, int maxCol, int minRow, int maxRow) {
    }
}
