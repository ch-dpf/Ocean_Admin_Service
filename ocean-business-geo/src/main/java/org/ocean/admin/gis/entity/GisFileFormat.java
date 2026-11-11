package org.ocean.admin.gis.entity;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文件组的入口格式及允许归入同组的配套文件规则。 */
public enum GisFileFormat {
    GEOJSON(true, Set.of("geojson", "json"), Set.of(), Set.of(), Set.of("GeoJSON")),
    GEOTIFF(false, Set.of("tif", "tiff"), Set.of(), Set.of("tfw", "tifw", "tiffw", "wld", "prj", "aux.xml", "msk", "ovr", "tab"), Set.of("GTiff")),
    SHP(true, Set.of("shp"), Set.of("shx", "dbf"), Set.of("prj", "cpg", "qix", "sbn", "sbx", "shp.xml"), Set.of("ESRI Shapefile")),
    DXF(true, Set.of("dxf"), Set.of(), Set.of("prj"), Set.of("DXF")),
    MIF(true, Set.of("mif"), Set.of("mid"), Set.of(), Set.of("MapInfo File")),
    TAB(true, Set.of("tab"), Set.of("dat", "map", "id"), Set.of("ind"), Set.of("MapInfo File")),
    CSV(true, Set.of("csv"), Set.of(), Set.of("csvt", "prj"), Set.of("CSV")),
    GPX(true, Set.of("gpx"), Set.of(), Set.of(), Set.of("GPX")),
    DWG(true, Set.of("dwg"), Set.of(), Set.of("prj"), Set.of("CAD", "DWG")),
    IMG(false, Set.of("img"), Set.of(), Set.of("ige", "rrd", "aux.xml", "msk", "ovr", "prj", "wld", "tab"), Set.of("HFA")),
    JPG(false, Set.of("jpg", "jpeg"), Set.of(), Set.of("jgw", "jpgw", "jpegw", "wld", "prj", "aux.xml", "tab", "msk", "ovr"), Set.of("JPEG")),
    PNG(false, Set.of("png"), Set.of(), Set.of("pgw", "pngw", "wld", "prj", "aux.xml", "tab", "msk", "ovr"), Set.of("PNG")),
    GIF(false, Set.of("gif"), Set.of(), Set.of("gfw", "gifw", "wld", "prj", "aux.xml", "tab", "msk", "ovr"), Set.of("GIF", "BIGGIF")),
    GRIB2(false, Set.of("grib2", "grb2", "grib", "grb"), Set.of(), Set.of("idx"), Set.of("GRIB"));

    private final boolean vector;
    private final Set<String> entryExtensions;
    private final Set<String> requiredExtensions;
    private final Set<String> optionalExtensions;
    private final Set<String> driverNames;

    GisFileFormat(boolean vector, Set<String> entryExtensions,
                  Set<String> requiredExtensions, Set<String> optionalExtensions, Set<String> driverNames) {
        this.vector = vector;
        this.entryExtensions = entryExtensions;
        this.requiredExtensions = requiredExtensions;
        this.optionalExtensions = optionalExtensions;
        this.driverNames = driverNames;
    }

    /** 只识别入口，配套文件不能单独声明为可解析数据源。 */
    public static GisFileFormat fromFileName(String name) {
        String extension = extension(name);
        for (GisFileFormat format : values()) {
            if (format.entryExtensions.contains(extension)) return format;
        }
        return null;
    }

    /** 这里只声明已接入解析链路的格式；实际接收还须检查运行环境能力。 */
    public List<Integer> categoryIds() { return vector ? List.of(2) : List.of(0, 1); }

    public boolean supportsCategory(Long categoryId) {
        return categoryId != null && categoryIds().stream().anyMatch(id -> id.longValue() == categoryId);
    }

    public enum Requirement { FORBIDDEN, OPTIONAL, REQUIRED }

    public record ReadOption(String key, String label, Requirement requirement) { }

    public Requirement sourceCrsRequirement() {
        return switch (this) {
            case GEOJSON -> Requirement.FORBIDDEN;
            case CSV, DXF, DWG -> Requirement.REQUIRED;
            default -> Requirement.OPTIONAL;
        };
    }

    /** 前端与后端参数校验共同消费本定义，不再各自判断格式名称。 */
    public List<ReadOption> readOptions() {
        Requirement coordinates = this == CSV ? Requirement.REQUIRED : Requirement.FORBIDDEN;
        return List.of(new ReadOption("sourceCrs", "源坐标系", sourceCrsRequirement()),
                new ReadOption("xField", "X 坐标字段", coordinates),
                new ReadOption("yField", "Y 坐标字段", coordinates));
    }

    public List<String> notices() {
        return switch (this) {
            case GEOJSON -> List.of("GeoJSON 按 RFC 7946 经度、纬度读取，不使用投影覆盖配置；源文件须具有明确的 WGS84 坐标。");
            case CSV -> List.of("请明确源坐标系和 X、Y 坐标字段，字段必须存在且不能相同。");
            case DXF -> List.of("CAD 文件须明确源坐标系。");
            case DWG -> List.of("CAD 文件须明确源坐标系；DWG 读取范围受驱动及文件版本影响。");
            case MIF -> List.of("无属性列的 MIF 可不带 MID，具体配套要求由文件内容核验。");
            default -> isRaster() ? List.of("投影、定位文件和掩膜随影像保留；栅格 TAB 表作为对应影像的配套。缺少定位信息时，请补齐配套或读取配置。") : List.of();
        };
    }

    public boolean acceptsMember(String name) {
        String extension = extension(name);
        return entryExtensions.contains(extension) || requiredExtensions.contains(extension) || optionalExtensions.contains(extension);
    }

    public static String extension(String name) {
        if (name == null) return "";
        String value = name.toLowerCase(Locale.ROOT);
        if (value.endsWith(".aux.xml")) return "aux.xml";
        if (value.endsWith(".shp.xml")) return "shp.xml";
        int index = value.lastIndexOf('.');
        return index < 0 ? "" : value.substring(index + 1);
    }

    public boolean isVector() { return vector; }
    public boolean isRaster() { return !vector; }
    public Set<String> entryExtensions() { return entryExtensions; }
    public Set<String> requiredExtensions() { return requiredExtensions; }
    public Set<String> optionalExtensions() { return optionalExtensions; }
    public Set<String> driverNames() { return driverNames; }
}
