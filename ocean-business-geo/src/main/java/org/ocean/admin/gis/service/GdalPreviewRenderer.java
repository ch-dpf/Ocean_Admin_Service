package org.ocean.admin.gis.service;

import org.gdal.gdal.*;
import org.gdal.gdalconst.gdalconstConstants;
import org.gdal.ogr.*;
import org.gdal.osr.CoordinateTransformation;
import org.gdal.osr.SpatialReference;
import org.ocean.admin.gis.entity.GisFileFormat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;

import javax.imageio.ImageIO;

/** 仅在隔离工作进程运行，按有限输出尺寸读取原始数据，不修改源文件。 */
public final class GdalPreviewRenderer {
    private GdalPreviewRenderer() {}

    public static ObjectNode execute(JsonNode request, Path directory, ObjectMapper json)
            throws Exception {
        Path input = Path.of(request.path("input").asText()).toAbsolutePath().normalize();
        if (!input.startsWith(directory.resolve("source")) || !Files.isRegularFile(input))
            throw new IllegalArgumentException("预览来源不在受控成员目录");
        GisFileFormat format = GisFileFormat.valueOf(request.path("format").asText());
        if (!format.isRaster()) throw new IllegalArgumentException("该操作仅支持栅格");
        Dataset container =
                gdal.OpenEx(
                        input.toString(),
                        gdalconstConstants.OF_RASTER | gdalconstConstants.OF_READONLY,
                        new Vector<>(format.driverNames()),
                        new Vector<>());
        if (container == null) throw new IllegalArgumentException("数据驱动无法读取来源");
        try {
            JsonNode parameters = request.path("preview");
            JsonNode root = info(container, json);
            List<String> names = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            JsonNode subsets = root.path("metadata").path("SUBDATASETS");
            for (String field : subsets.propertyNames().stream().sorted().toList()) {
                if (!field.matches("SUBDATASET_[1-9][0-9]*_NAME")) continue;
                String name = subsets.path(field).asText();
                if (!name.contains(input.toString())
                        || name.contains("/vsi")
                        || name.contains("://")) throw new IllegalArgumentException("子数据集引用超出当前来源");
                names.add(name);
                keys.add("subdataset:" + field.substring(11, field.length() - 5));
            }
            if (names.isEmpty()) {
                names.add(input.toString());
                keys.add("default");
            }
            if (names.size() > 128) throw new IllegalArgumentException("子数据集超过128个预览上限");
            ObjectNode result = json.createObjectNode();
            if ("DESCRIBE".equals(parameters.path("action").asText())) {
                var layers = result.putArray("layers");
                for (int i = 0; i < names.size(); i++) {
                    Dataset source = null;
                    try {
                        source =
                                names.size() == 1 && keys.getFirst().equals("default")
                                        ? container
                                        : gdal.OpenEx(
                                                names.get(i),
                                                gdalconstConstants.OF_RASTER
                                                        | gdalconstConstants.OF_READONLY,
                                                new Vector<>(format.driverNames()),
                                                new Vector<>());
                        layers.add(describe(source, keys.get(i), request.path("options"), json));
                    } catch (RuntimeException ex) {
                        layers.addObject()
                                .put("key", keys.get(i))
                                .put("imageAvailable", false)
                                .put("mapAvailable", false)
                                .put("imageReason", "该集合当前不可读取，可选择其他集合或检查来源数据。");
                    } finally {
                        if (source != container && source != null) source.delete();
                    }
                }
                return result;
            }
            String key = parameters.path("layerKey").asText("default");
            int index = keys.indexOf(key);
            if (index < 0) throw new IllegalArgumentException("选择的子数据集不存在");
            Dataset source =
                    key.equals("default")
                            ? container
                            : gdal.OpenEx(
                                    names.get(index),
                                    gdalconstConstants.OF_RASTER | gdalconstConstants.OF_READONLY,
                                    new Vector<>(format.driverNames()),
                                    new Vector<>());
            try {
                if (source == null) throw new IllegalArgumentException("子数据集不可读取");
                return render(source, request.path("options"), parameters, directory, json);
            } finally {
                if (source != container && source != null) source.delete();
            }
        } finally {
            container.delete();
        }
    }

    private static JsonNode info(Dataset source, ObjectMapper json) {
        InfoOptions options = new InfoOptions(new Vector<>(List.of("-json", "-mdd", "all")));
        try {
            return json.readTree(gdal.GDALInfo(source, options));
        } finally {
            options.delete();
        }
    }

    private static ObjectNode describe(
            Dataset source, String key, JsonNode options, ObjectMapper json) {
        if (source == null || source.GetRasterCount() == 0)
            throw new IllegalArgumentException("集合没有可读取波段");
        JsonNode metadata = info(source, json);
        ObjectNode result =
                json.createObjectNode()
                        .put("key", key)
                        .put("width", source.GetRasterXSize())
                        .put("height", source.GetRasterYSize())
                        .put("imageAvailable", false)
                        .put("mapAvailable", false);
        var bands = result.putArray("bands");
        if (source.GetRasterCount() > 4096) throw new IllegalArgumentException("波段超过4096个预览上限");
        for (JsonNode band : metadata.path("bands")) {
            ObjectNode item = bands.addObject();
            for (String field : List.of("band", "description"))
                if (band.has(field)) item.set(field, band.get(field));
            boolean renderable =
                    !band.path("type").asText().startsWith("C")
                            && !band.path("type").asText().equals("Unknown");
            item.put("renderable", renderable);
            ObjectNode grib = item.putObject("grib");
            JsonNode values = band.path("metadata").path("");
            for (String field : List.of("GRIB_ELEMENT", "GRIB_SHORT_NAME", "GRIB_VALID_TIME"))
                if (values.has(field)) grib.set(field, values.get(field));
        }
        try {
            bands(source, json.createObjectNode());
            result.put("imageAvailable", true);
        } catch (IllegalArgumentException ex) {
            return result.put("imageReason", "该集合仅包含复数或不支持的波段，请先明确需要展示的物理量。");
        }
        String crs = sourceCrs(source, options, metadata);
        result.put("sourceCrs", crs);
        if (crs == null || crs.isBlank()) return result.put("mapReason", "缺少源坐标系，仅可查看图像");
        if (!metadata.has("geoTransform")
                && !metadata.has("gcps")
                && metadata.path("metadata").path("RPC").isEmpty())
            return result.put("mapReason", "缺少仿射、GCP或RPC定位信息，仅可查看图像");
        Dataset projected = null;
        WarpOptions warp = null;
        try {
            warp =
                    new WarpOptions(
                            new Vector<>(
                                    List.of("-of", "VRT", "-s_srs", crs, "-t_srs", "EPSG:4326")));
            projected = gdal.Warp("", new Dataset[] {source}, warp);
            if (projected == null) return result.put("mapReason", "坐标转换不可用");
            double[] t = projected.GetGeoTransform();
            double west = t[0], north = t[3];
            double east = west + projected.GetRasterXSize() * t[1];
            double south = north + projected.GetRasterYSize() * t[5];
            if (!Double.isFinite(west + east + north + south) || west >= east || south >= north)
                return result.put("mapReason", "转换后的地理范围无效");
            // 单个包围框无法表达跨日期变更线的两个区间，使用全经度范围避免截掉数据。
            double span = east - west;
            west -= 360 * Math.floor((west + 180) / 360);
            east = west + span;
            if (span >= 360 || east > 180) {
                west = -180;
                east = 180;
            }
            south = Math.max(-90, south);
            north = Math.min(90, north);
            if (!Double.isFinite(west + east + north + south) || west >= east || south >= north)
                return result.put("mapReason", "转换后的地理范围无效");
            result.set(
                    "bounds",
                    json.valueToTree(
                            new double[] {west, Math.max(-90, south), east, Math.min(90, north)}));
            result.put("mapAvailable", true);
        } catch (RuntimeException ex) {
            result.put("mapReason", "坐标转换失败，请检查读取配置");
        } finally {
            if (projected != null) projected.delete();
            if (warp != null) warp.delete();
        }
        return result;
    }

    /** 与接入读取保持相同定位规则：显式坐标系、数据集投影、GCP投影、RPC经纬度。 */
    private static String sourceCrs(Dataset source, JsonNode options, JsonNode metadata) {
        String crs = options.path("sourceCrs").asText(source.GetProjection());
        if (crs != null && !crs.isBlank()) return crs;
        crs = metadata.path("gcps").path("coordinateSystem").path("wkt").asText("");
        if (!crs.isBlank()) return crs;
        return metadata.path("metadata").path("RPC").isEmpty() ? "" : "EPSG:4326";
    }

    /** RGB优先采用颜色解释，其次采用无歧义的颜色名称；无颜色语义时显示单波段。 */
    private static List<Integer> bands(Dataset source, JsonNode parameters) {
        List<Integer> selected = new ArrayList<>();
        if (parameters.path("bands").isArray())
            parameters.path("bands").forEach(value -> selected.add(value.asInt()));
        else {
            int[] colors = {
                gdalconstConstants.GCI_RedBand,
                gdalconstConstants.GCI_GreenBand,
                gdalconstConstants.GCI_BlueBand
            };
            int[] interpreted = new int[3];
            int[] named = new int[3];
            for (int i = 1; i <= source.GetRasterCount(); i++) {
                Band band = source.GetRasterBand(i);
                try {
                    String type = gdal.GetDataTypeName(band.GetRasterDataType());
                    if (type.startsWith("C") || type.equals("Unknown")) continue;
                    int interpretation = band.GetColorInterpretation();
                    for (int channel = 0; channel < colors.length; channel++) {
                        if (interpretation == colors[channel] && interpreted[channel] == 0)
                            interpreted[channel] = i;
                    }
                    // 名称仅补充缺失的颜色语义，不能覆盖显式RGB、调色板或Alpha标记。
                    if (interpretation != gdalconstConstants.GCI_Undefined
                            && interpretation != gdalconstConstants.GCI_GrayIndex) continue;
                    String description = band.GetDescription();
                    if (description == null) continue;
                    int channel =
                            switch (description.trim().toLowerCase(Locale.ROOT)) {
                                case "red" -> 0;
                                case "green" -> 1;
                                case "blue" -> 2;
                                default -> -1;
                            };
                    if (channel < 0) continue;
                    if (named[channel] == 0) named[channel] = i;
                    else named[channel] = -1;
                } finally {
                    band.delete();
                }
            }
            for (int channel = 0; channel < colors.length; channel++) {
                int index = interpreted[channel];
                if (index == 0) index = named[channel];
                if (index > 0) selected.add(index);
            }
            if (selected.size() != 3) {
                selected.clear();
                for (int i = 1; i <= source.GetRasterCount(); i++) {
                    Band band = source.GetRasterBand(i);
                    try {
                        String type = gdal.GetDataTypeName(band.GetRasterDataType());
                        if (!type.startsWith("C") && !type.equals("Unknown")) {
                            selected.add(i);
                            break;
                        }
                    } finally {
                        band.delete();
                    }
                }
            }
        }
        if (selected.size() != 1 && selected.size() != 3)
            throw new IllegalArgumentException("请选择一个数值波段或三个RGB波段");
        for (int index : selected) {
            if (index < 1 || index > source.GetRasterCount())
                throw new IllegalArgumentException("波段不存在");
            Band band = source.GetRasterBand(index);
            try {
                if (gdal.GetDataTypeName(band.GetRasterDataType()).startsWith("C"))
                    throw new IllegalArgumentException("复数波段需要先明确物理量转换");
            } finally {
                band.delete();
            }
        }
        return selected;
    }

    private static ObjectNode render(
            Dataset source,
            JsonNode options,
            JsonNode parameters,
            Path directory,
            ObjectMapper json)
            throws Exception {
        int width = parameters.path("width").asInt(1024),
                height = parameters.path("height").asInt(768);
        if (width < 1 || height < 1 || width > 2048 || height > 2048)
            throw new IllegalArgumentException("预览尺寸须为1至2048");
        List<Integer> selected = bands(source, parameters);
        boolean indexedColor = false;
        for (int index : selected) {
            Band band = source.GetRasterBand(index);
            try {
                indexedColor |=
                        band.GetColorInterpretation() == gdalconstConstants.GCI_PaletteIndex;
            } finally {
                band.delete();
            }
        }
        Vector<String> selection = new Vector<>(List.of("-of", "VRT"));
        for (int index : selected) selection.addAll(List.of("-b", Integer.toString(index)));
        String crs = sourceCrs(source, options, info(source, json));
        // 像素查看不需要坐标转换，错误的定位配置不应阻断原始图像读取。
        if (parameters.has("bbox") && crs != null && !crs.isBlank())
            selection.addAll(List.of("-a_srs", crs));
        // 明确携带Alpha，以便重投影后的无数据区域保持透明。
        for (int i = 1; i <= source.GetRasterCount(); i++) {
            Band band = source.GetRasterBand(i);
            try {
                if (band.GetColorInterpretation() == gdalconstConstants.GCI_AlphaBand
                        && !selected.contains(i)) {
                    selection.addAll(List.of("-b", Integer.toString(i)));
                    break;
                }
            } finally {
                band.delete();
            }
        }
        TranslateOptions translate = new TranslateOptions(selection);
        Dataset chosen = gdal.Translate("", source, translate);
        translate.delete();
        Dataset display = null;
        try {
            if (parameters.has("bbox")) {
                if (crs == null || crs.isBlank()) throw new IllegalArgumentException("缺少源坐标系");
                double[] box = bounds(parameters.path("bbox"));
                String target = parameters.path("crs").asText("EPSG:3857");
                if (!Set.of("EPSG:3857", "EPSG:4326", "CRS:84").contains(target))
                    throw new IllegalArgumentException("目标坐标系不受支持");
                Vector<String> args =
                        new Vector<>(
                                List.of(
                                        "-of",
                                        "MEM",
                                        "-t_srs",
                                        target.equals("CRS:84") ? "OGC:CRS84" : target,
                                        "-te",
                                        Double.toString(box[0]),
                                        Double.toString(box[1]),
                                        Double.toString(box[2]),
                                        Double.toString(box[3]),
                                        "-ts",
                                        Integer.toString(width),
                                        Integer.toString(height),
                                        "-r",
                                        "near",
                                        "-dstalpha",
                                        "-wm",
                                        "32"));
                WarpOptions warp = new WarpOptions(args);
                try {
                    display = gdal.Warp("", new Dataset[] {chosen}, warp);
                } finally {
                    warp.delete();
                }
            } else {
                double factor =
                        Math.min(
                                1,
                                Math.min(
                                        (double) width / source.GetRasterXSize(),
                                        (double) height / source.GetRasterYSize()));
                width = Math.max(1, (int) Math.round(source.GetRasterXSize() * factor));
                height = Math.max(1, (int) Math.round(source.GetRasterYSize() * factor));
                TranslateOptions resize =
                        new TranslateOptions(
                                new Vector<>(
                                        List.of(
                                                "-of",
                                                "MEM",
                                                "-outsize",
                                                Integer.toString(width),
                                                Integer.toString(height))));
                try {
                    display = gdal.Translate("", chosen, resize);
                } finally {
                    resize.delete();
                }
            }
            if (display == null) throw new IllegalArgumentException("图像生成失败");
            int count = width * height;
            int[] pixels = new int[count];
            Arrays.fill(pixels, 0xff000000);
            ObjectNode result = json.createObjectNode().put("width", width).put("height", height);
            for (int channel = 0; channel < selected.size(); channel++) {
                Band original = source.GetRasterBand(selected.get(channel));
                Band band = display.GetRasterBand(channel + 1);
                Band mask = band.GetMaskBand();
                ColorTable palette = null;
                try {
                    double[] values = new double[count];
                    byte[] validity = new byte[count];
                    check(band.ReadRaster(0, 0, width, height, values));
                    check(mask.ReadRaster(0, 0, width, height, validity));
                    Double[] noData = new Double[1];
                    band.GetNoDataValue(noData);
                    double[] range =
                            indexedColor
                                    ? new double[] {0, 255}
                                    : range(original, selected.size() == 3);
                    if (selected.size() == 1
                            && original.GetColorInterpretation()
                                    == gdalconstConstants.GCI_PaletteIndex)
                        palette = original.GetRasterColorTable();
                    for (int i = 0; i < count; i++) {
                        double value = values[i];
                        if (validity[i] == 0
                                || !Double.isFinite(value)
                                || noData[0] != null && Double.compare(value, noData[0]) == 0) {
                            pixels[i] = 0;
                            continue;
                        }
                        int color =
                                Math.max(
                                        0,
                                        Math.min(
                                                255,
                                                (int)
                                                        Math.round(
                                                                255
                                                                        * (value - range[0])
                                                                        / (range[1] - range[0]))));
                        if (selected.size() == 3) pixels[i] |= color << (16 - 8 * channel);
                        else if (palette != null && value >= 0 && value < palette.GetCount())
                            pixels[i] =
                                    palette.GetColorEntry((int) value).getRGB()
                                            & (pixels[i] | 0x00ffffff);
                        else pixels[i] |= color << 16 | color << 8 | color;
                        int alpha = Math.min(pixels[i] >>> 24, Byte.toUnsignedInt(validity[i]));
                        pixels[i] = (pixels[i] & 0x00ffffff) | alpha << 24;
                    }
                } finally {
                    if (palette != null) palette.delete();
                    mask.delete();
                    band.delete();
                    original.delete();
                }
            }
            // Warp生成的目标Alpha可能不作为数据波段的默认mask，必须显式应用。
            for (int index = 1; index <= display.GetRasterCount(); index++) {
                Band alphaBand = display.GetRasterBand(index);
                try {
                    if (alphaBand.GetColorInterpretation() != gdalconstConstants.GCI_AlphaBand)
                        continue;
                    double[] alpha = new double[count];
                    check(alphaBand.ReadRaster(0, 0, width, height, alpha));
                    double maximum =
                            alphaBand.GetRasterDataType() == gdalconstConstants.GDT_UInt16
                                    ? 65535
                                    : 255;
                    for (int i = 0; i < count; i++) {
                        int value =
                                Math.max(
                                        0,
                                        Math.min(255, (int) Math.round(alpha[i] * 255 / maximum)));
                        pixels[i] =
                                (pixels[i] & 0x00ffffff) | Math.min(pixels[i] >>> 24, value) << 24;
                    }
                } finally {
                    alphaBand.delete();
                }
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            ImageIO.write(image, "PNG", directory.resolve("preview.png").toFile());
            return result;
        } finally {
            if (display != null) display.delete();
            if (chosen != null) chosen.delete();
        }
    }

    /** 非Byte的RGB采用2%至98%累计裁剪；统计限定在源波段的65536个抽样像元内。 */
    private static double[] range(Band band, boolean cumulativeCut) {
        if (band.GetRasterDataType() == gdalconstConstants.GDT_Byte) return new double[] {0, 255};
        int width = Math.min(256, band.GetXSize()), height = Math.min(256, band.GetYSize());
        double[] values = new double[width * height];
        byte[] valid = new byte[values.length];
        check(
                band.ReadRaster(
                        0,
                        0,
                        band.GetXSize(),
                        band.GetYSize(),
                        width,
                        height,
                        gdalconstConstants.GDT_Float64,
                        values));
        Band mask = band.GetMaskBand();
        try {
            check(
                    mask.ReadRaster(
                            0,
                            0,
                            band.GetXSize(),
                            band.GetYSize(),
                            width,
                            height,
                            gdalconstConstants.GDT_Byte,
                            valid));
        } finally {
            mask.delete();
        }
        Double[] noData = new Double[1];
        band.GetNoDataValue(noData);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int validCount = 0;
        for (int i = 0; i < values.length; i++) {
            if (valid[i] == 0
                    || !Double.isFinite(values[i])
                    || noData[0] != null && Double.compare(values[i], noData[0]) == 0) continue;
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
            values[validCount++] = values[i];
        }
        if (validCount == 0) return new double[] {0, 1};
        if (cumulativeCut) {
            Arrays.sort(values, 0, validCount);
            double lower = values[(int) Math.floor((validCount - 1) * 0.02)];
            double upper = values[(int) Math.floor((validCount - 1) * 0.98)];
            // 分位值相同时保留抽样范围，避免常量或稀疏影像的显示范围退化。
            if (lower < upper) {
                min = lower;
                max = upper;
            }
        }
        return new double[] {min, max == min ? min + Math.max(1, Math.abs(min) * 0.01) : max};
    }

    /** 一次游标查询同时产生表格和图形；不按地图视窗额外读取其他要素。 */
    public static ObjectNode vector(JsonNode request, ObjectMapper json) {
        String table = request.path("table").asText();
        boolean describe = request.path("view").asText().equals("describe");
        if (!describe && !table.matches("l_[0-9]+")) throw new IllegalArgumentException("来源集合无效");
        long after = request.path("after").asLong();
        int limit = request.path("limit").asInt();
        if (!describe
                && (!request.path("view").asText().equals("page")
                        || after < 0
                        || limit < 1
                        || limit > 50)) throw new IllegalArgumentException("预览分页参数无效");
        DataSource data = ogr.Open(request.path("input").asText(), 0);
        if (data == null) throw new IllegalArgumentException("原始快照不可读取");
        SpatialReference geographic = reference("EPSG:4326");
        CoordinateTransformation forward = null;
        Layer rows = null;
        try {
            if (describe) {
                ObjectNode result = json.createObjectNode();
                if (data.GetLayerCount() > 128) throw new IllegalArgumentException("主文件集合超过预览上限");
                for (int i = 0; i < data.GetLayerCount(); i++) {
                    Layer item = data.GetLayer(i);
                    if (item.GetName().matches("l_[0-9]+"))
                        result.set(item.GetName(), describeVector(item, json));
                }
                return result;
            }
            Layer layer = data.GetLayerByName(table);
            if (layer == null) throw new IllegalArgumentException("原始集合不存在");
            SpatialReference source = layer.GetSpatialRef();
            if (source != null && (source.IsGeographic() == 1 || source.IsProjected() == 1)) {
                try {
                    source.SetAxisMappingStrategy(0);
                    forward = new CoordinateTransformation(source, geographic);
                } catch (RuntimeException ex) {
                    forward = null;
                }
            }
            // 属性在SQLite内与完整几何分离，避免单个大几何先触发旧查询的8MiB上限。
            String properties = "json_extract(feature_json,'$.properties')";
            rows =
                    data.ExecuteSQL(
                            "SELECT fid, CASE WHEN length(CAST("
                                    + properties
                                    + " AS BLOB))<=16384 THEN "
                                    + properties
                                    + " ELSE NULL END AS preview_properties, COALESCE(length(CAST("
                                    + properties
                                    + " AS BLOB)),0) AS property_bytes, COALESCE(length(geom),0) AS"
                                    + " geometry_bytes FROM "
                                    + table
                                    + " WHERE fid>"
                                    + after
                                    + " ORDER BY fid LIMIT "
                                    + (limit + 1),
                            null,
                            "SQLITE");
            if (rows == null) throw new IllegalArgumentException("预览分页读取失败");
            ObjectNode result = json.createObjectNode();
            var items = result.putArray("rows");
            Feature row;
            long last = after;
            while ((row = rows.GetNextFeature()) != null) {
                try {
                    if (items.size() == limit) {
                        result.put("nextAfter", Long.toString(last));
                        break;
                    }
                    last = row.GetFID();
                    ObjectNode item = items.addObject().put("sourceRowId", Long.toString(last));
                    String attributes = row.GetFieldAsString("preview_properties");
                    item.put(
                            "featureJson",
                            "{\"properties\":"
                                    + (attributes == null || attributes.isBlank()
                                            ? "{}"
                                            : attributes)
                                    + "}");
                    if (row.GetFieldAsInteger64("property_bytes") > 16384)
                        item.put("propertyNotice", "属性内容过大，未在预览中展开");
                    item.putNull("geometry")
                            .putNull("mapGeometry")
                            .put("geometryStatus", "EMPTY")
                            .put("mapStatus", "EMPTY");
                    long geometryBytes = row.GetFieldAsInteger64("geometry_bytes");
                    if (geometryBytes == 0) continue;
                    if (geometryBytes > 8 * 1024 * 1024) {
                        item.put("geometryStatus", "LIMIT").put("mapStatus", "LIMIT");
                        continue;
                    }
                    Layer shapes = null;
                    Feature shape = null;
                    try {
                        // 仅取当前行的有界几何，不把sourceGeometries或完整feature_json带入响应。
                        shapes =
                                data.ExecuteSQL(
                                        "SELECT geom FROM " + table + " WHERE fid=" + last,
                                        null,
                                        "SQLITE");
                        if (shapes == null) throw new IllegalArgumentException("图形不可读取");
                        shape = shapes.GetNextFeature();
                        if (shape != null)
                            previewGeometry(shape.GetGeometryRef(), forward, item, json);
                    } catch (RuntimeException ex) {
                        item.put("geometryStatus", "UNAVAILABLE").put("mapStatus", "UNAVAILABLE");
                    } finally {
                        if (shape != null) shape.delete();
                        if (shapes != null) data.ReleaseResultSet(shapes);
                    }
                } finally {
                    row.delete();
                }
            }
            return result;
        } finally {
            if (rows != null) data.ReleaseResultSet(rows);
            if (forward != null) forward.delete();
            geographic.delete();
            data.delete();
        }
    }

    /** 显示几何最多2000个顶点及32KiB；简化只作用于临时副本，不能改变来源。 */
    private static void previewGeometry(
            Geometry original,
            CoordinateTransformation forward,
            ObjectNode item,
            ObjectMapper json) {
        if (original == null || original.IsEmpty()) return;
        Geometry linear = original.GetLinearGeometry();
        Geometry display = linear;
        String encoded = null;
        boolean simplified = false;
        try {
            double[] envelope = new double[4];
            linear.GetEnvelope(envelope);
            double span = Math.max(envelope[1] - envelope[0], envelope[3] - envelope[2]);
            for (int attempt = 0; attempt <= 8; attempt++) {
                if (display != null
                        && !display.IsEmpty()
                        && geometryPoints(display, 2000) <= 2000) {
                    String candidate = display.ExportToJson();
                    if (candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            <= 32768) {
                        encoded = candidate;
                        break;
                    }
                }
                if (attempt == 8 || !Double.isFinite(span) || span <= 0) break;
                if (display != linear && display != null) display.delete();
                display = null;
                display = linear.SimplifyPreserveTopology(span / 65536 * Math.pow(4, attempt));
                simplified = true;
            }
            if (encoded == null) {
                item.put("geometryStatus", "LIMIT").put("mapStatus", "LIMIT");
                return;
            }
            item.set("geometry", json.readTree(encoded));
            String status = simplified ? "SIMPLIFIED" : "READY";
            item.put("geometryStatus", status).put("mapStatus", "UNAVAILABLE");
            if (forward == null) return;
            Geometry projected = display.Clone();
            try {
                check(projected.Transform(forward));
                double[] bounds = new double[4];
                projected.GetEnvelope(bounds);
                if (!java.util.Arrays.stream(bounds).allMatch(Double::isFinite)
                        || bounds[0] < -180
                        || bounds[1] > 180
                        || bounds[2] < -90
                        || bounds[3] > 90) return;
                String map = projected.ExportToJson();
                if (map.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768) {
                    item.put("mapStatus", "LIMIT");
                    return;
                }
                item.set("mapGeometry", json.readTree(map));
                item.put("mapStatus", status);
            } catch (RuntimeException ex) {
                item.put("mapStatus", "UNAVAILABLE");
            } finally {
                projected.delete();
            }
        } finally {
            if (display != linear && display != null) display.delete();
            linear.delete();
        }
    }

    private static int geometryPoints(Geometry geometry, int budget) {
        int count = geometry.GetPointCount();
        for (int i = 0; count <= budget && i < geometry.GetGeometryCount(); i++) {
            Geometry part = geometry.GetGeometryRef(i);
            try {
                count += geometryPoints(part, budget - count);
            } finally {
                part.delete();
            }
        }
        return count;
    }

    /** 仅使用快照缓存范围，不为打开预览扫描整个文件；地理能力须通过实际坐标转换。 */
    private static ObjectNode describeVector(Layer layer, ObjectMapper json) {
        ObjectNode result =
                json.createObjectNode().put("drawingAvailable", false).put("mapAvailable", false);
        try {
            double[] extent = layer.GetExtent(false);
            if (extent == null
                    || extent.length != 4
                    || !java.util.Arrays.stream(extent).allMatch(Double::isFinite)
                    || extent[0] > extent[1]
                    || extent[2] > extent[3]) return result;
            result.put("drawingAvailable", true)
                    .set(
                            "drawingBounds",
                            json.valueToTree(
                                    new double[] {extent[0], extent[2], extent[1], extent[3]}));
            SpatialReference source = layer.GetSpatialRef();
            if (source == null || (source.IsGeographic() == 0 && source.IsProjected() == 0))
                return result;
            source.SetAxisMappingStrategy(0);
            SpatialReference target = reference("EPSG:4326");
            CoordinateTransformation conversion = null;
            try {
                conversion = new CoordinateTransformation(source, target);
                double[] box = new double[4];
                conversion.TransformBounds(box, extent[0], extent[2], extent[1], extent[3], 21);
                if (!java.util.Arrays.stream(box).allMatch(Double::isFinite)
                        || box[1] > box[3]
                        || box[1] < -90
                        || box[3] > 90) return result;
                if (box[0] > box[2] || box[0] < -180 || box[2] > 180) {
                    box[0] = -180;
                    box[2] = 180;
                }
                result.put("mapAvailable", true).set("bounds", json.valueToTree(box));
            } finally {
                if (conversion != null) conversion.delete();
                target.delete();
            }
        } catch (RuntimeException ex) {
            // 定位失败不影响原坐标图形与属性查看。
            result.put("mapAvailable", false);
        }
        return result;
    }

    public static double[] bounds(JsonNode node) {
        if (!node.isArray() || node.size() != 4) throw new IllegalArgumentException("范围须包含四个坐标");
        double[] box = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!node.get(i).isNumber() || !Double.isFinite(node.get(i).asDouble()))
                throw new IllegalArgumentException("范围坐标无效");
            box[i] = node.get(i).asDouble();
        }
        if (box[0] >= box[2] || box[1] >= box[3]) throw new IllegalArgumentException("范围最小值须小于最大值");
        return box;
    }

    private static SpatialReference reference(String definition) {
        SpatialReference value = new SpatialReference();
        try {
            check(value.SetFromUserInput(definition.equals("CRS:84") ? "OGC:CRS84" : definition));
            value.SetAxisMappingStrategy(0);
            return value;
        } catch (RuntimeException ex) {
            value.delete();
            throw ex;
        }
    }

    private static void check(int code) {
        if (code != 0) throw new IllegalArgumentException("原始数据读取或坐标转换失败");
    }
}
