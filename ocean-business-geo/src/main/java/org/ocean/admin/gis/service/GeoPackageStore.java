package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.gdal.ogr.*;
import org.gdal.osr.SpatialReference;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Vector;

/** 托管 GPKG 的物理存储；所有原生读取均在既有隔离工作进程中执行。 */
@Service
@RequiredArgsConstructor
public class GeoPackageStore {
    private final GisProcessingProperties properties;
    private final GdalDatasetReader reader;
    private final ObjectMapper json;

    public Path root() throws IOException {
        Path root = Path.of(properties.getBasePath()).toAbsolutePath().normalize().resolve("vector-resources");
        Files.createDirectories(root);
        return root;
    }

    public Path path(String key) throws IOException {
        if (key == null || !key.matches("[0-9]+\\.(gpkg|ndjson|partial)"))
            throw new IllegalArgumentException("矢量存储键无效");
        Path path = root().resolve(key);
        if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("矢量存储不能是符号链接");
        return path;
    }

    public void install(Path input, long id) throws IOException {
        Path pending = path(id + ".partial");
        Files.copy(input, pending);
        try { Files.move(pending, path(id + ".gpkg"), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(pending, path(id + ".gpkg")); }
    }

    public String checksum(String key) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path(key))) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = stream.read(bytes)) >= 0) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public JsonNode build(long id, JsonNode layers, Runnable heartbeat) throws IOException {
        ObjectNode request = json.createObjectNode().put("packageOperation", "BUILD")
                .put("input", path(id + ".ndjson").toString()).put("output", path(id + ".partial").toString());
        request.set("layers", layers);
        JsonNode result = reader.executePackage(request, heartbeat);
        Files.move(path(id + ".partial"), path(id + ".gpkg"));
        Files.delete(path(id + ".ndjson"));
        return result;
    }

    public JsonNode query(String key, String table, long after, int limit, double[] bbox, Long fid) throws IOException {
        if (!table.matches("l_[0-9]+") || after < 0 || limit < 1 || limit > 1001)
            throw new IllegalArgumentException("矢量查询参数无效");
        ObjectNode request = json.createObjectNode().put("packageOperation", "QUERY")
                .put("input", path(key).toString()).put("table", table).put("after", after).put("limit", limit);
        if (bbox != null) request.set("bbox", json.valueToTree(bbox));
        if (fid != null) request.put("fid", fid);
        return reader.executePackage(request, () -> { });
    }

    /** 批量转换只启动一次进程，避免每100个要素重新打开大文件。 */
    public void export(String key, List<Long> layerIds, long outputId) throws IOException {
        ObjectNode request = json.createObjectNode().put("packageOperation", "EXPORT")
                .put("input", path(key).toString()).put("output", path(outputId + ".ndjson").toString());
        request.set("layerIds", json.valueToTree(layerIds));
        reader.executePackage(request, () -> { });
    }

    public byte[] tile(String key, JsonNode definition, int z, int x, int y, int maxBytes) throws IOException {
        ObjectNode request=json.createObjectNode().put("packageOperation","TILE").put("input",path(key).toString())
                .put("z",z).put("x",x).put("y",y).put("maxBytes",maxBytes);
        request.set("definition",definition);
        JsonNode result=reader.executePackage(request,()->{});
        byte[] compressed=java.util.Base64.getDecoder().decode(result.path("body").asText());
        try(var gzip=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            byte[] bytes=gzip.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes) throw new IllegalArgumentException("瓦片超过解码限额");
            return bytes;
        }
    }

    /** 静态任务一次启动一个进程并复用数据源，不经动态瓦片HTTP接口。 */
    public void staticTiles(Path input, JsonNode definition, List<int[]> ranges, Path output,
                            int maxBytes, int maxCompressedBytes, Runnable heartbeat) {
        ObjectNode request = json.createObjectNode().put("packageOperation", "TILES")
                .put("input", input.toString()).put("output", output.toString())
                .put("maxBytes", maxBytes).put("maxCompressedBytes", maxCompressedBytes)
                .put("maxOutputBytes", properties.getGdalMaxOutputBytes());
        request.set("definition", definition);
        request.set("ranges", json.valueToTree(ranges));
        reader.executePackage(request, heartbeat);
    }

    /** 仅由隔离 Worker 调用，路径均由主服务构造，客户端不能提交文件路径或 SQL。 */
    public static ObjectNode executeNative(JsonNode request, Path directory, ObjectMapper json) throws Exception {
        return switch (request.path("packageOperation").asText()) {
            case "SNAPSHOT" -> snapshot(request, directory, json);
            case "BUILD" -> buildNative(request, json);
            case "QUERY" -> queryNative(request, json);
            case "TILE" -> tileNative(request, json);
            case "TILES" -> staticTilesNative(request, json);
            case "EXPORT" -> exportNative(request, json);
            default -> throw new IllegalArgumentException("未知矢量包操作");
        };
    }

    private static ObjectNode exportNative(JsonNode request, ObjectMapper json) throws IOException {
        DataSource data = ogr.Open(request.path("input").asText(), 0);
        if (data == null) throw new IllegalStateException("矢量包不可读取");
        long count = 0;
        try (var writer = Files.newBufferedWriter(Path.of(request.path("output").asText()), StandardOpenOption.CREATE_NEW)) {
            for (JsonNode id : request.path("layerIds")) {
                if (id.asLong() < 1) throw new IllegalArgumentException("工作图层ID无效");
                Layer rows = data.ExecuteSQL("SELECT feature_json FROM l_" + id.asLong() + " ORDER BY fid");
                if (rows == null) throw new IllegalStateException("导出图层不存在");
                try {
                    Feature row;
                    while ((row = rows.GetNextFeature()) != null) {
                        try { writer.write(row.GetFieldAsString("feature_json")); writer.newLine(); count++; }
                        finally { row.delete(); }
                    }
                } finally { data.ReleaseResultSet(rows); }
            }
        } finally { data.delete(); }
        return json.createObjectNode().put("featureCount", count);
    }

    /** 一次打开文件完成一块瓦片，避免每页要素启动一次工作进程。 */
    private static ObjectNode tileNative(JsonNode request,ObjectMapper json) throws IOException {
        DataSource data = ogr.Open(request.path("input").asText(), 0);
        if (data == null) throw new IllegalStateException("矢量包不可读取");
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write(encodeTile(request, json, data)); }
            return json.createObjectNode().put("body", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } finally { data.delete(); }
    }

    private static ObjectNode staticTilesNative(JsonNode request, ObjectMapper json) throws IOException {
        DataSource data = ogr.Open(request.path("input").asText(), 0);
        if (data == null) throw new IllegalStateException("矢量工作快照不可读取");
        Path output = Path.of(request.path("output").asText()).toAbsolutePath().normalize();
        long completed = 0;
        long outputBytes = 0;
        try {
            ObjectNode tileRequest = (ObjectNode) request.deepCopy();
            for (JsonNode range : request.path("ranges")) {
                int z = range.get(0).asInt();
                for (int x = range.get(1).asInt(); x <= range.get(2).asInt(); x++) {
                    Path directory = output.resolve(Integer.toString(z)).resolve(Integer.toString(x));
                    Files.createDirectories(directory);
                    for (int y = range.get(3).asInt(); y <= range.get(4).asInt(); y++) {
                        tileRequest.put("z", z).put("x", x).put("y", y);
                        byte[] body = encodeTile(tileRequest, json, data);
                        outputBytes += body.length;
                        if (outputBytes > request.path("maxOutputBytes").asLong())
                            throw new IllegalStateException("静态矢量成果超过磁盘产物限额");
                        var compressed = new java.io.ByteArrayOutputStream();
                        try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(body); }
                        if (compressed.size() > request.path("maxCompressedBytes").asInt())
                            throw new IllegalStateException("静态瓦片超过压缩体积上限: " + z + "/" + x + "/" + y);
                        Files.write(directory.resolve(y + ".mvt"), body, StandardOpenOption.CREATE_NEW);
                        completed++;
                    }
                }
            }
            return json.createObjectNode().put("tileCount", completed);
        } finally { data.delete(); }
    }

    private static byte[] encodeTile(JsonNode request, ObjectMapper json, DataSource data) throws IOException {
        JsonNode definition=request.path("definition");
        var encoder=new org.ocean.admin.gis.util.VectorTileEncoder(json,new org.ocean.admin.gis.util.VectorTemplateRules(json,new org.ocean.admin.gis.util.VectorStyleRules(json)));
        var tile=encoder.begin(request.path("z").asInt(),request.path("x").asInt(),request.path("y").asInt(),definition.path("tilePolicy"),request.path("maxBytes").asInt());
        var groups=new java.util.HashMap<Long,String>();
        for(JsonNode group:definition.path("groups")) groups.put(group.path("id").asLong(),group.path("groupKey").asText());
        double[] b=tile.bbox();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        for(JsonNode node:definition.path("layers")) {
            var layer=json.treeToValue(node,org.ocean.admin.gis.entity.GisVectorLayer.class);
            String table="l_"+layer.getId();
            Layer rows=data.ExecuteSQL("SELECT feature_json FROM "+table+" WHERE fid IN (SELECT id FROM rtree_"+table+"_geom WHERE minx<="+b[2]+" AND maxx>="+b[0]+" AND miny<="+b[3]+" AND maxy>="+b[1]+") ORDER BY fid");
            if(rows==null) throw new IllegalStateException("瓦片源图层不存在");
            try {
                Feature row;
                while((row=rows.GetNextFeature())!=null) {
                    try {
                        if(System.nanoTime()>deadline) throw new IllegalStateException("文件瓦片查询超时");
                        var feature=json.readValue(row.GetFieldAsString("feature_json"),org.ocean.admin.gis.entity.GisVectorFeature.class);
                        tile.add(feature,layer,groups.get(layer.getGroupId()));
                    } finally {row.delete();}
                }
            } finally {data.ReleaseResultSet(rows);}
        }
        return tile.finish();
    }

    private static DataSource create(Path path) {
        Driver driver = ogr.GetDriverByName("GPKG");
        if (driver == null) throw new IllegalStateException("GDAL 缺少 GPKG 写入驱动");
        if (Files.exists(path)) throw new IllegalStateException("禁止覆盖已存在的矢量包");
        DataSource data = driver.CreateDataSource(path.toString());
        if (data == null) throw new IllegalStateException("GPKG 创建失败");
        return data;
    }

    private static Layer createLayer(DataSource data, String table, SpatialReference srs) {
        Layer layer = data.CreateLayer(table, srs, ogr.wkbUnknown, new Vector<>(List.of("SPATIAL_INDEX=YES", "GEOMETRY_NAME=geom", "FID=fid")));
        FieldDefn field = new FieldDefn("feature_json", ogr.OFTString);
        try { check(layer.CreateField(field)); } finally { field.delete(); }
        return layer;
    }

    private static void append(Layer layer, long fid, JsonNode value, JsonNode geometry, ObjectMapper json) {
        Feature row = new Feature(layer.GetLayerDefn());
        try {
            check(row.SetFID(fid));
            row.SetField("feature_json", json.writeValueAsString(value));
            if (geometry != null && !geometry.isNull()) {
                Geometry shape = ogr.CreateGeometryFromJson(json.writeValueAsString(geometry));
                if (shape == null) throw new IllegalArgumentException("几何不能完整存入快照");
                try { check(row.SetGeometry(shape)); } finally { shape.delete(); }
            }
            check(layer.CreateFeature(row));
        } finally { row.delete(); }
    }

    private static ObjectNode snapshot(JsonNode request, Path directory, ObjectMapper json) throws Exception {
        Path input = Path.of(request.path("input").asText());
        if (!input.toAbsolutePath().normalize().startsWith(directory.resolve("source")))
            throw new IllegalArgumentException("快照只能读取受控文件组");
        DataSource target = create(directory.resolve("snapshot.gpkg"));
        ObjectNode result = json.createObjectNode();
        var layers = result.putArray("layers");
        try {
            check(target.StartTransaction());
            if ("GEOJSON".equals(request.path("format").asText())) {
                SpatialReference srs = new SpatialReference();
                srs.ImportFromEPSG(4326);
                try {
                    Layer output = createLayer(target, "l_0", srs);
                    long count = 0;
                    ObjectNode fields = json.createObjectNode();
                    ObjectNode envelope = json.createObjectNode();
                    var nodeReader = json.readerFor(JsonNode.class).without(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                    try (var parser = json.createParser(input.toFile())) {
                        if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("GeoJSON 根对象无效");
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String name = parser.currentName(); parser.nextToken();
                            if ("features".equals(name)) {
                                if (parser.currentToken() != JsonToken.START_ARRAY) throw new IllegalArgumentException("features 须为数组");
                                while (parser.nextToken() != JsonToken.END_ARRAY) {
                                    JsonNode feature = nodeReader.readValue(parser);
                                    append(output, ++count, feature, feature.get("geometry"), json);
                                    for (String field : feature.path("properties").propertyNames())
                                        fields.put(field, "JSON");
                                }
                            } else envelope.set(name, nodeReader.readValue(parser));
                        }
                    }
                    if ("Feature".equals(envelope.path("type").asText())) {
                        append(output, ++count, envelope, envelope.get("geometry"), json);
                        for (String field : envelope.path("properties").propertyNames()) fields.put(field, "JSON");
                    } else if (!"FeatureCollection".equals(envelope.path("type").asText()))
                        throw new IllegalArgumentException("原始 GeoJSON 须为 Feature 或 FeatureCollection");
                    ObjectNode layer = layers.addObject().put("key", "default").put("name", input.getFileName().toString())
                            .put("table", "l_0").put("featureCount", count).put("sourceCrs", "EPSG:4326");
                    layer.set("fields", fields); layer.set("sourceEnvelope", envelope);
                } finally { srs.delete(); }
            } else {
                var options = new Vector<String>();
                JsonNode readOptions = request.path("options");
                if ("CSV".equals(request.path("format").asText())) {
                    options.add("X_POSSIBLE_NAMES=" + readOptions.path("xField").asText());
                    options.add("Y_POSSIBLE_NAMES=" + readOptions.path("yField").asText());
                }
                var source = org.gdal.gdal.gdal.OpenEx(input.toString(), org.gdal.gdalconst.gdalconstConstants.OF_VECTOR,
                        new Vector<>(), options);
                if (source == null) throw new IllegalStateException("源文件无法打开");
                try {
                    for (int i = 0; i < source.GetLayerCount(); i++) {
                        Layer inputLayer = source.GetLayer(i);
                        SpatialReference srs = inputLayer.GetSpatialRef();
                        if (readOptions.hasNonNull("sourceCrs")) {
                            srs = new SpatialReference(); srs.SetFromUserInput(readOptions.path("sourceCrs").asText());
                        }
                        String table = "l_" + i;
                        Layer output = createLayer(target, table, srs);
                        ObjectNode description = layers.addObject().put("key", "layer:" + HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(inputLayer.GetName().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                                .put("name", inputLayer.GetName()).put("table", table)
                                .put("sourceCrs", srs == null ? null : srs.ExportToWkt());
                        var fields = description.putArray("fields");
                        var definition = inputLayer.GetLayerDefn();
                        for (int f = 0; f < definition.GetFieldCount(); f++) {
                            var field = definition.GetFieldDefn(f);
                            fields.addObject().put("name", field.GetName()).put("type", field.GetTypeName());
                        }
                        long count = 0;
                        Feature feature;
                        while ((feature = inputLayer.GetNextFeature()) != null) {
                            try {
                                ObjectNode value = json.createObjectNode().put("type", "Feature").put("id", feature.GetFID());
                                Geometry geometry = feature.GetGeometryRef();
                                value.set("geometry", geometry == null ? json.nullNode() : json.readTree(geometry.ExportToJson()));
                                var attributes = value.putObject("properties");
                                for (int f = 0; f < definition.GetFieldCount(); f++) {
                                    var field = definition.GetFieldDefn(f);
                                    if (!feature.IsFieldSet(f)) continue;
                                    if (feature.IsFieldNull(f)) { attributes.putNull(field.GetName()); continue; }
                                    JsonNode fieldValue = switch (field.GetFieldType()) {
                                        case ogr.OFTInteger -> json.valueToTree(feature.GetFieldAsInteger(f));
                                        case ogr.OFTInteger64 -> json.valueToTree(feature.GetFieldAsInteger64(f));
                                        case ogr.OFTReal -> json.valueToTree(feature.GetFieldAsDouble(f));
                                        case ogr.OFTIntegerList -> json.valueToTree(feature.GetFieldAsIntegerList(f));
                                        case ogr.OFTRealList -> json.valueToTree(feature.GetFieldAsDoubleList(f));
                                        case ogr.OFTStringList -> json.valueToTree(feature.GetFieldAsStringList(f));
                                        default -> json.valueToTree(feature.GetFieldAsString(f));
                                    };
                                    attributes.set(field.GetName(), fieldValue);
                                }
                                append(output, ++count, value, value.get("geometry"), json);
                            } finally { feature.delete(); }
                        }
                        description.put("featureCount", count);
                    }
                } finally { source.delete(); }
            }
            check(target.CommitTransaction());
            result.put("preservation", "SOURCE_FEATURES");
            result.put("sourceFormat", request.path("format").asText());
            return result;
        } finally { target.delete(); }
    }

    private static ObjectNode buildNative(JsonNode request, ObjectMapper json) throws IOException {
        DataSource target = create(Path.of(request.path("output").asText()));
        SpatialReference srs = new SpatialReference(); srs.ImportFromEPSG(4326);
        var layers = new java.util.HashMap<String, Layer>();
        try {
            check(target.StartTransaction());
            for (JsonNode definition : request.path("layers")) {
                String table = definition.path("table").asText();
                if (!table.matches("l_[0-9]+")) throw new IllegalArgumentException("表名无效");
                layers.put(table, createLayer(target, table, srs));
            }
            long count = 0;
            try (var stream = Files.newBufferedReader(Path.of(request.path("input").asText()))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    JsonNode row = json.readTree(line);
                    Layer layer = layers.get("l_" + row.path("layerId").asText());
                    if (layer == null) throw new IllegalArgumentException("要素图层未登记");
                    append(layer, row.path("id").asLong(), row, json.readTree(row.path("geometryJson").asText()), json);
                    count++;
                }
            }
            check(target.CommitTransaction());
            return json.createObjectNode().put("featureCount", count);
        } finally { target.delete(); srs.delete(); }
    }

    private static ObjectNode queryNative(JsonNode request, ObjectMapper json) {
        String table = request.path("table").asText();
        if (!table.matches("l_[0-9]+")) throw new IllegalArgumentException("表名无效");
        DataSource data = ogr.Open(request.path("input").asText(), 0);
        if (data == null) throw new IllegalStateException("矢量包不可读取");
        try {
            int limit = request.path("limit").asInt();
            if (limit < 1 || limit > 1001) throw new IllegalArgumentException("查询条数无效");
            String predicate = request.has("fid") ? "fid=" + request.path("fid").asLong()
                    : "fid>" + request.path("after").asLong();
            JsonNode box = request.path("bbox");
            Geometry filter = null;
            if (box.isArray()) {
                if (box.size() != 4) throw new IllegalArgumentException("范围无效");
                double[] b = new double[4];
                for (int i = 0; i < 4; i++) {
                    b[i] = box.get(i).asDouble();
                    if (!Double.isFinite(b[i])) throw new IllegalArgumentException("范围无效");
                }
                String longitude = b[0] <= b[2] ? "minx<=" + b[2] + " AND maxx>=" + b[0]
                        : "(maxx>=" + b[0] + " OR minx<=" + b[2] + ")";
                predicate += " AND fid IN (SELECT id FROM rtree_" + table + "_geom WHERE " + longitude
                        + " AND miny<=" + b[3] + " AND maxy>=" + b[1] + ")";
                if (b[0] <= b[2]) filter = rectangle(b[0], b[1], b[2], b[3]);
                else {
                    filter = new Geometry(ogr.wkbMultiPolygon);
                    Geometry west = rectangle(b[0], b[1], 180, b[3]);
                    Geometry east = rectangle(-180, b[1], b[2], b[3]);
                    try { check(filter.AddGeometry(west)); check(filter.AddGeometry(east)); }
                    finally { west.delete(); east.delete(); }
                }
            }
            Layer rows;
            try {
                rows = data.ExecuteSQL("SELECT fid, feature_json" + (filter == null ? "" : ",geom") + " FROM " + table + " WHERE " + predicate
                        + " ORDER BY fid" + (filter == null ? " LIMIT " + limit : ""));
            } catch (RuntimeException ex) { if (filter != null) filter.delete(); throw ex; }
            if (rows == null) throw new IllegalStateException("矢量包查询失败");
            ObjectNode result = json.createObjectNode(); var items = result.putArray("items");
            long bytes = 0;
            try {
                Feature feature;
                while (items.size() < limit && (feature = rows.GetNextFeature()) != null) {
                    try {
                        // RTree只筛选候选包围盒，精确相交避免孔洞和弯曲线的误命中。
                        if (filter != null && (feature.GetGeometryRef() == null || !feature.GetGeometryRef().Intersects(filter))) continue;
                        String value = feature.GetFieldAsString("feature_json");
                        bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (bytes > 8 * 1024 * 1024) throw new IllegalArgumentException("查询结果超过8MiB，请减小每页条数或查询范围");
                        ObjectNode item = items.addObject().put("fid", feature.GetFID());
                        item.set("value", json.readTree(value));
                    } finally { feature.delete(); }
                }
            } finally { data.ReleaseResultSet(rows); if (filter != null) filter.delete(); }
            return result;
        } finally { data.delete(); }
    }

    private static Geometry rectangle(double west, double south, double east, double north) {
        return ogr.CreateGeometryFromWkt("POLYGON((" + west + " " + south + "," + east + " " + south + ","
                + east + " " + north + "," + west + " " + north + "," + west + " " + south + "))");
    }

    private static void check(int code) {
        if (code != 0) throw new IllegalStateException("GPKG 操作失败，错误码 " + code);
    }
}
