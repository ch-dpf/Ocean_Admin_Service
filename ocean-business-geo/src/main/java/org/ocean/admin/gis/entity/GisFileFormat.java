package org.ocean.admin.gis.entity;

import java.util.Locale;
import java.util.Set;

/** 文件组的入口格式及允许归入同组的配套文件规则。 */
public enum GisFileFormat {
    GEOJSON(true, true, Set.of("geojson", "json"), Set.of(), Set.of(), Set.of("GeoJSON")),
    GEOTIFF(false, true, Set.of("tif", "tiff"), Set.of(), Set.of("tfw", "tifw", "tiffw", "wld", "prj", "aux.xml", "msk", "ovr", "tab"), Set.of("GTiff")),
    SHP(true, false, Set.of("shp"), Set.of("shx", "dbf"), Set.of("prj", "cpg", "qix", "sbn", "sbx", "shp.xml"), Set.of("ESRI Shapefile")),
    DXF(true, false, Set.of("dxf"), Set.of(), Set.of("prj"), Set.of("DXF")),
    MIF(true, false, Set.of("mif"), Set.of("mid"), Set.of(), Set.of("MapInfo File")),
    TAB(true, false, Set.of("tab"), Set.of("dat", "map", "id"), Set.of("ind"), Set.of("MapInfo File")),
    CSV(true, false, Set.of("csv"), Set.of(), Set.of("csvt", "prj"), Set.of("CSV")),
    GPX(true, false, Set.of("gpx"), Set.of(), Set.of(), Set.of("GPX")),
    DWG(true, false, Set.of("dwg"), Set.of(), Set.of("prj"), Set.of("CAD", "DWG")),
    IMG(false, false, Set.of("img"), Set.of(), Set.of("ige", "rrd", "aux.xml", "msk", "ovr", "prj", "wld", "tab"), Set.of("HFA")),
    JPG(false, false, Set.of("jpg", "jpeg"), Set.of(), Set.of("jgw", "jpgw", "jpegw", "wld", "prj", "aux.xml", "tab", "msk", "ovr"), Set.of("JPEG")),
    PNG(false, false, Set.of("png"), Set.of(), Set.of("pgw", "pngw", "wld", "prj", "aux.xml", "tab", "msk", "ovr"), Set.of("PNG")),
    GIF(false, false, Set.of("gif"), Set.of(), Set.of("gfw", "gifw", "wld", "prj", "aux.xml", "tab", "msk", "ovr"), Set.of("GIF", "BIGGIF")),
    GRIB2(false, false, Set.of("grib2", "grb2", "grib", "grb"), Set.of(), Set.of("idx"), Set.of("GRIB"));

    private final boolean vector;
    private final boolean nativeReader;
    private final Set<String> entryExtensions;
    private final Set<String> requiredExtensions;
    private final Set<String> optionalExtensions;
    private final Set<String> driverNames;

    GisFileFormat(boolean vector, boolean nativeReader, Set<String> entryExtensions,
                  Set<String> requiredExtensions, Set<String> optionalExtensions, Set<String> driverNames) {
        this.vector = vector;
        this.nativeReader = nativeReader;
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

    /** 分类接入允许保留配套文件，能否解析由完整文件组决定。 */
    public static boolean accepts(Long categoryId, String name) {
        if (categoryId == null) return false;
        String extension = extension(name);
        // 已允许管理但尚无解析器的格式仍可保存，其文件组保持待识别状态。
        if (categoryId == 0 && Set.of("jp2", "aux", "xml").contains(extension)) return true;
        if (categoryId == 1 && Set.of("dem", "hgt", "terrain", "asc", "xml").contains(extension)) return true;
        if (categoryId == 2 && Set.of("kml", "kmz", "gpkg").contains(extension)) return true;
        for (GisFileFormat format : values()) {
            if (((categoryId == 2 && format.isVector()) || ((categoryId == 0 || categoryId == 1) && format.isRaster()))
                    && format.acceptsMember(name)) return true;
        }
        return false;
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
    public boolean isNative() { return nativeReader; }
    public Set<String> entryExtensions() { return entryExtensions; }
    public Set<String> requiredExtensions() { return requiredExtensions; }
    public Set<String> optionalExtensions() { return optionalExtensions; }
    public Set<String> driverNames() { return driverNames; }
}
