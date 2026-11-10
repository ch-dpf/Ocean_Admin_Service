package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.gdal.ogr.*;
import org.gdal.osr.SpatialReference;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.util.VectorTileEncoder;
import org.ocean.admin.gis.util.VectorTemplateRules;
import org.ocean.admin.gis.util.VectorStyleRules;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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
    private static final int[] DISPLAY_LEVELS = {3, 6, 10, 14, 18};
    private final GisProcessingProperties properties;
    private final GdalDatasetReader reader;
    private final ObjectMapper json;

    public Path root() throws IOException {
        Path root = Path.of(properties.getBasePath()).toAbsolutePath().normalize().resolve("vector-resources");
        Files.createDirectories(root);
        return root;
    }

    /** 托管 GPKG 的物理存储；任务工作区 GPKG 使用绝对路径并校验位于处理根目录内。 */
    public Path path(String key) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("矢量存储键无效");
        }
        Path path = Path.of(key).toAbsolutePath().normalize();
        if (!path.startsWith(Path.of(properties.getBasePath()).toAbsolutePath().normalize())) {
            if (!key.matches("[0-9]+\\.(gpkg|ndjson|partial)")) {
                throw new IllegalArgumentException("矢量存储键无效");
            }
            path = root().resolve(key);
        }
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("矢量存储不能是符号链接");
        }
        return path;
    }

    public void install(Path input, long id) throws IOException {
        install(input, id, () -> { }, stage -> { });
    }

    /** 已提交的工作包在数据集锁内按字节复制，复用空间索引并上报真实复制进度。 */
    public void install(Path input, long id, Runnable heartbeat,
                        java.util.function.Consumer<org.ocean.admin.gis.progress.ProgressStage> progress) throws IOException {
        Path pending = path(id + ".partial");
        long total = Files.size(input), copied = 0, lastReport = 0;
        try (var source = Files.newInputStream(input);
             var target = Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8 * 1024 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                heartbeat.run();
                target.write(buffer, 0, count);
                copied += count;
                long now = System.nanoTime();
                if (now - lastReport >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1) || copied == total) {
                    progress.accept(org.ocean.admin.gis.progress.ProgressStage.determinate("VECTOR_SNAPSHOT",
                            "复制工作数据快照 " + copied + "/" + total + " 字节", copied, total,
                            org.ocean.admin.gis.progress.ProgressUnit.BYTE));
                    lastReport = now;
                }
            }
        }
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

    public JsonNode build(long id, JsonNode definition, Runnable heartbeat) throws IOException {
        ObjectNode request = json.createObjectNode().put("packageOperation", "BUILD")
                .put("input", path(id + ".ndjson").toString()).put("output", path(id + ".partial").toString());
        request.set("definition", definition);
        JsonNode result = reader.executePackage(request, heartbeat);
        Files.move(path(id + ".partial"), path(id + ".gpkg"));
        Files.delete(path(id + ".ndjson"));
        return result;
    }

    public JsonNode edit(String key, long operationId, JsonNode mutation) throws IOException {
        ObjectNode request = (ObjectNode) mutation.deepCopy();
        request.put("packageOperation", "EDIT").put("input", path(key).toString()).put("operationId", operationId);
        return reader.executePackage(request, () -> { });
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

    /**
     * 一次打开冻结包读取多个图层的窗口要素，供 OGC 服务端渲染使用。
     *
     * <p>与逐层分页查询相比，多图层只启动一次隔离工作进程；返回项携带图层 ID，
     * 由调用方按图层样式分别绘制。</p>
     */
    public JsonNode queryWindow(String key, List<Long> layerIds, double[] bbox,
            int maxBytes, int limit) throws IOException {
        if (layerIds == null || layerIds.isEmpty() || layerIds.size() > 512
                || bbox == null || bbox.length != 4 || maxBytes < 1024 || limit < 1) {
            throw new IllegalArgumentException("窗口查询参数无效");
        }
        ObjectNode request = json.createObjectNode().put("packageOperation", "WINDOW")
                .put("input", path(key).toString())
                .put("maxBytes", Math.min(maxBytes, 8 * 1024 * 1024))
                .put("limit", limit);
        request.set("layerIds", json.valueToTree(layerIds));
        request.set("bbox", json.valueToTree(bbox));
        JsonNode result = reader.executePackage(request, () -> { });
        byte[] compressed = java.util.Base64.getDecoder().decode(result.path("body").asText());
        try (var gzip = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(compressed))) {
            byte[] bytes = gzip.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IllegalArgumentException("窗口要素超过解码限额");
            }
            return json.readTree(bytes);
        }
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

    public byte[] tile(JsonNode sources, JsonNode definition, int z, int x, int y, int maxBytes) throws IOException {
        ObjectNode request = json.createObjectNode().put("packageOperation", "TILE")
                .put("z", z).put("x", x).put("y", y).put("maxBytes", maxBytes);
        request.set("definition", definition);
        request.set("sources", sources);
        JsonNode result = reader.executePackage(request, () -> { });
        try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(
                java.util.Base64.getDecoder().decode(result.path("body").asText())))) {
            byte[] bytes = gzip.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IllegalArgumentException("瓦片超过解码限额");
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

    /** 栅格绘图复用受控进程和进度协议，不进入动态瓦片工作池。 */
    public void staticRasterTiles(Path input, JsonNode definition, JsonNode scheme, List<int[]> ranges,
                                  Path output, long timeoutSeconds,
                                  java.util.function.Consumer<org.ocean.admin.gis.progress.ProgressStage> progress) {
        ObjectNode request = json.createObjectNode().put("packageOperation", "RASTER_TILES")
                .put("input", input.toString()).put("output", output.toString())
                .put("timeoutSeconds", timeoutSeconds).put("maxOutputBytes", properties.getGdalMaxOutputBytes());
        request.set("definition", definition);
        request.set("scheme", scheme);
        request.set("ranges", json.valueToTree(ranges));
        reader.executePackage(request, () -> {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("栅格切片任务已中断");
        }, progress);
    }

    /** 仅由隔离 Worker 调用，路径均由主服务构造，客户端不能提交文件路径或 SQL。 */
    public static ObjectNode executeNative(JsonNode request, Path directory, ObjectMapper json) throws Exception {
        return switch (request.path("packageOperation").asText()) {
            case "SNAPSHOT" -> snapshot(request, directory, json);
            case "BUILD" -> buildNative(request, json);
            case "QUERY" -> queryNative(request, json);
            case "TILE" -> tileNative(request, json);
            case "TILES" -> staticTilesNative(request, json);
            case "RASTER_TILES" -> new org.ocean.admin.gis.vector.VectorRasterTileGenerator(json).generate(request);
            case "WMS_MAP" -> new org.ocean.admin.gis.vector.VectorRasterTileGenerator(json).map(request);
            case "WINDOW" -> windowNative(request, json);
            case "EXPORT" -> exportNative(request, json);
            case "EDIT" -> editNative(request, json);
            default -> throw new IllegalArgumentException("未知矢量包操作");
        };
    }

    /** 一次打开冻结包，按 RTree 粗筛后精确相交，返回多个图层的 WGS84 几何与属性。 */
    private static ObjectNode windowNative(JsonNode request, ObjectMapper json) throws IOException {
        JsonNode box = request.path("bbox");
        if (!box.isArray() || box.size() != 4) throw new IllegalArgumentException("窗口范围无效");
        double west = box.get(0).asDouble(), south = box.get(1).asDouble();
        double east = box.get(2).asDouble(), north = box.get(3).asDouble();
        if (!Double.isFinite(west) || !Double.isFinite(south) || !Double.isFinite(east)
                || !Double.isFinite(north) || west >= east || south >= north) {
            throw new IllegalArgumentException("窗口范围无效");
        }
        int limit = request.path("limit").asInt();
        int maxBytes = request.path("maxBytes").asInt();
        if (limit < 1 || limit > 20000 || maxBytes < 1024) {
            throw new IllegalArgumentException("窗口查询限额无效");
        }
        String longitude = "minx<=" + east + " AND maxx>=" + west;
        Geometry filter = rectangle(west, south, east, north);
        DataSource data = ogr.Open(request.path("input").asText(), 0);
        if (data == null) {
            filter.delete();
            throw new IllegalStateException("矢量包不可读取");
        }
        ArrayNode items = json.createArrayNode();
        long bytes = 0;
        try {
            for (JsonNode idNode : request.path("layerIds")) {
                if (idNode.asLong() < 1) throw new IllegalArgumentException("工作图层ID无效");
                String table = "l_" + idNode.asLong();
                Layer rows = data.ExecuteSQL("SELECT feature_json,geom FROM " + table
                        + " WHERE fid IN (SELECT id FROM rtree_" + table + "_geom WHERE " + longitude
                        + " AND miny<=" + north + " AND maxy>=" + south + ") ORDER BY fid LIMIT " + limit);
                if (rows == null) throw new IllegalStateException("瓦片源图层不存在");
                try {
                    Feature row;
                    while ((row = rows.GetNextFeature()) != null) {
                        try {
                            // RTree 只筛选包围盒，再用窗口多边形精确相交排除孔洞与弯曲线误命中。
                            if (row.GetGeometryRef() == null
                                    || !row.GetGeometryRef().Intersects(filter)) {
                                continue;
                            }
                            String value = row.GetFieldAsString("feature_json");
                            bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                            if (bytes > maxBytes || items.size() >= limit) {
                                throw new IllegalArgumentException("窗口要素超过解码或数量限额");
                            }
                            JsonNode feature = json.readTree(value);
                            ObjectNode item = items.addObject().put("layerId", idNode.asLong());
                            item.put("id", feature.path("id").asText(""));
                            item.set("properties", json.readTree(
                                    feature.path("propertiesJson").asText("{}")));
                            item.set("geometry", json.readTree(row.GetGeometryRef().ExportToJson()));
                        } finally {
                            row.delete();
                        }
                    }
                } finally {
                    data.ReleaseResultSet(rows);
                }
            }
            var compressed = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) {
                gzip.write(json.writeValueAsBytes(items));
            }
            return json.createObjectNode().put("body",
                    java.util.Base64.getEncoder().encodeToString(compressed.toByteArray()));
        } finally {
            data.delete();
            filter.delete();
        }
    }

    /** 动态地图读取独立发布包；请求路径由托管存储构造。 */
    public byte[] map(String key, JsonNode definition, JsonNode scheme, String crs, double[] bounds,
                      int width, int height, boolean transparent, int background) throws IOException {
        ObjectNode request = json.createObjectNode().put("packageOperation", "WMS_MAP")
                .put("input", path(key).toString()).put("crs", crs).put("width", width).put("height", height)
                .put("transparent", transparent).put("background", background);
        request.set("bounds", json.valueToTree(bounds));
        request.set("definition", definition);
        request.set("scheme", scheme);
        JsonNode result = reader.executePackage(request, () -> { });
        return java.util.Base64.getDecoder().decode(result.path("body").asText());
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
        var bytes = new java.io.ByteArrayOutputStream();
        var encoder = encoder(json);
        var tile = encoder.begin(request.path("z").asInt(), request.path("x").asInt(), request.path("y").asInt(),
                request.path("definition").path("tilePolicy"), request.path("maxBytes").asInt());
        if (request.has("sources")) {
            for (JsonNode source : request.path("sources")) {
                DataSource data = ogr.Open("SQLite:" + source.path("input").asText(), 0);
                if (data == null) throw new IllegalStateException("矢量包不可读取");
                try { appendTile(request, json, data, source.path("layers"), tile); }
                finally { data.delete(); }
            }
        } else {
            DataSource data = ogr.Open("SQLite:" + request.path("input").asText(), 0);
            if (data == null) throw new IllegalStateException("矢量包不可读取");
            try { appendTile(request, json, data, request.path("definition").path("layers"), tile); }
            finally { data.delete(); }
        }
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write(tile.finish()); }
        return json.createObjectNode().put("body", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }

    private static ObjectNode staticTilesNative(JsonNode request, ObjectMapper json) throws IOException {
        DataSource data = ogr.Open("SQLite:" + request.path("input").asText(), 0);
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
        var tile = encoder(json).begin(request.path("z").asInt(), request.path("x").asInt(), request.path("y").asInt(),
                request.path("definition").path("tilePolicy"), request.path("maxBytes").asInt());
        appendTile(request, json, data, request.path("definition").path("layers"), tile);
        return tile.finish();
    }

    private static VectorTileEncoder encoder(ObjectMapper json) {
        return new VectorTileEncoder(json, new VectorTemplateRules(json, new VectorStyleRules(json)));
    }

    private static void appendTile(JsonNode request, ObjectMapper json, DataSource data, JsonNode layers,
                                   VectorTileEncoder.Tile tile) throws IOException {
        JsonNode definition = request.path("definition");
        JsonNode policy = definition.path("tilePolicy");
        int z = request.path("z").asInt();
        var groups=new java.util.HashMap<Long,String>();
        for(JsonNode group:definition.path("groups")) groups.put(group.path("id").asLong(),group.path("groupKey").asText());
        double size = VectorTileEncoder.WORLD_SIZE / Math.scalb(1, z);
        double buffer = size * policy.path("buffer").asDouble(256) / policy.path("extent").asDouble(8192);
        double west = -VectorTileEncoder.WORLD_SIZE / 2 + request.path("x").asInt() * size - buffer;
        double east = west + size + 2 * buffer;
        double north = VectorTileEncoder.WORLD_SIZE / 2 - request.path("y").asInt() * size + buffer;
        double south = north - size - 2 * buffer;
        double businessZoom = VectorTemplateRules.tileBusinessZoom(z, policy);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        for(JsonNode node:layers) {
            var layer=json.treeToValue(node,GisVectorLayer.class);
            String groupKey = groups.get(layer.getGroupId());
            JsonNode group = null;
            for (JsonNode candidate : policy.path("groups")) if (candidate.path("groupKey").asText().equals(groupKey)) { group = candidate; break; }
            if (group == null || businessZoom < group.path("minZoom").asDouble()) continue;
            boolean preserve = group.path("preserveBoundary").asBoolean();
            readDisplay(data, json, layer, policy, z, businessZoom, size / policy.path("tileSize").asDouble(512), preserve,
                    new org.locationtech.jts.geom.Envelope(west, east, south, north), deadline,
                    (id, display) -> tile.addPrepared(id, layer, groupKey, display, preserve));
        }
    }

    /** 动态编码和静态绘图共用只读空间索引查询，不加载全部工作要素。 */
    public static void readDisplay(DataSource data, ObjectMapper json, GisVectorLayer layer, JsonNode policy,
                                   int z, double businessZoom, double pixel, boolean preserve,
                                   org.locationtech.jts.geom.Envelope bounds, long deadline,
                                   java.util.function.BiConsumer<Long, VectorTileEncoder.Display> consumer) throws IOException {
        if (layer.getId() == null || layer.getId() < 1 || z < 0 || z > 18 || !Double.isFinite(businessZoom))
            throw new IllegalArgumentException("显示数据查询参数无效");
        var wkb = new org.locationtech.jts.io.WKBReader();
        int level = 18;
        if (!preserve && !"POINT".equals(layer.getGeometryType()))
            for (int candidate : DISPLAY_LEVELS) if (candidate >= z) { level = candidate; break; }
        String table="d_"+layer.getId()+"_"+level;
        String filter = "min_zoom<=" + businessZoom;
        if (!preserve && z < 18) {
            if ("POLYGON".equals(layer.getGeometryType())) filter += " AND area>=" + policy.path("polygonMinAreaPixels").asDouble() * pixel * pixel;
            if ("LINE".equals(layer.getGeometryType())) filter += " AND length>=" + policy.path("lineMinLengthPixels").asDouble() * pixel;
        }
        // 只读瓦片直接查询 SQLite 和现有 RTree，避免 GPKG 驱动逐层初始化全部显示表。
        // ID 使用文本列，防止 SQLite 驱动按首行推断成 32 位整数。
        Layer rows=data.ExecuteSQL("SELECT CAST(fid AS TEXT) AS feature_id,geom AS geometry_blob,attributes,min_zoom,area,length,anchor_x,anchor_y FROM "+table+
                " WHERE " + filter + " AND fid IN (SELECT id FROM rtree_"+table+"_geom WHERE minx<="+bounds.getMaxX()+
                " AND maxx>="+bounds.getMinX()+" AND miny<="+bounds.getMaxY()+" AND maxy>="+bounds.getMinY()+") ORDER BY fid");
        if(rows==null) throw new IllegalStateException("瓦片源图层不存在");
        try {
            Feature row;
            while((row=rows.GetNextFeature())!=null) {
                try {
                    if(System.nanoTime()>deadline) throw new IllegalStateException("文件瓦片查询超时");
                    if (row.IsFieldNull("geometry_blob")) continue;
                    byte[] geometry = row.GetFieldAsBinary("geometry_blob");
                    if (geometry.length < 8 || geometry[0] != 'G' || geometry[1] != 'P' || geometry[2] != 0
                            || (geometry[3] & 0xe0) != 0)
                        throw new IOException("显示几何的 GeoPackage 头无效");
                    // 标准 GPKG 头之后是 WKB；包络维度仅决定头长度，不改变几何坐标。
                    int headerLength = switch ((geometry[3] >>> 1) & 7) {
                        case 0 -> 8;
                        case 1 -> 40;
                        case 2, 3 -> 56;
                        case 4 -> 72;
                        default -> throw new IOException("显示几何的包络标记无效");
                    };
                    if (geometry.length <= headerLength) throw new IOException("显示几何的 WKB 缺失");
                    if ((geometry[3] & 0x10) != 0) continue;
                    var shape = wkb.read(java.util.Arrays.copyOfRange(geometry, headerLength, geometry.length));
                    var anchor = row.IsFieldNull("anchor_x") ? null : new org.locationtech.jts.geom.Coordinate(
                            row.GetFieldAsDouble("anchor_x"), row.GetFieldAsDouble("anchor_y"));
                    var display = new VectorTileEncoder.Display(shape, anchor, row.GetFieldAsDouble("area"),
                            row.GetFieldAsDouble("length"), row.GetFieldAsDouble("min_zoom"), json.readTree(row.GetFieldAsString("attributes")));
                    consumer.accept(Long.parseLong(row.GetFieldAsString("feature_id")), display);
                } catch (org.locationtech.jts.io.ParseException ex) {
                    throw new IOException("显示几何无法解码", ex);
                } finally {row.delete();}
            }
        } finally {data.ReleaseResultSet(rows);}
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
        FieldDefn kind = new FieldDefn("geometry_type", ogr.OFTString);
        try { check(layer.CreateField(kind)); } finally { kind.delete(); }
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
                row.SetField("geometry_type", geometry.path("type").asText().toUpperCase(java.util.Locale.ROOT));
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
        JsonNode definition = request.path("definition");
        var descriptions = new java.util.HashMap<Long, GisVectorLayer>();
        var expectedCounts = new java.util.HashMap<Long, Long>();
        try {
            check(target.StartTransaction());
            for (JsonNode node : definition.path("layers")) {
                String table = "l_" + node.path("id").asLong();
                if (!table.matches("l_[0-9]+")) throw new IllegalArgumentException("表名无效");
                layers.put(table, createLayer(target, table, srs));
                descriptions.put(node.path("id").asLong(), json.treeToValue(node, GisVectorLayer.class));
                createDisplayLayers(target, json.treeToValue(node, GisVectorLayer.class), definition);
            }
            execute(target, "CREATE TABLE ocean_edit_journal(operation_id INTEGER PRIMARY KEY,result TEXT NOT NULL)");
            var encoder = encoder(json);
            long count = 0;
            try (var stream = Files.newBufferedReader(Path.of(request.path("input").asText()))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    JsonNode row = json.readTree(line);
                    Layer layer = layers.get("l_" + row.path("layerId").asText());
                    if (layer == null) throw new IllegalArgumentException("要素图层未登记");
                    append(layer, row.path("id").asLong(), row, json.readTree(row.path("geometryJson").asText()), json);
                    appendDisplay(target, encoder, json.treeToValue(row, GisVectorFeature.class), descriptions.get(row.path("layerId").asLong()), definition, json);
                    expectedCounts.merge(row.path("layerId").asLong(), 1L, Long::sum);
                    count++;
                }
            }
            check(target.CommitTransaction());
            for (var entry : layers.entrySet()) execute(target, "CREATE INDEX idx_" + entry.getKey() + "_kind ON " + entry.getKey() + "(geometry_type)");
            ObjectNode result = json.createObjectNode().put("featureCount", count);
            var statistics = result.putArray("layers");
            for (long id : descriptions.keySet()) {
                ObjectNode actual = layerStatistics(target, id, json);
                if (actual.path("count").asLong() != expectedCounts.getOrDefault(id, 0L))
                    throw new IOException("工作包要素数量校验失败: " + id);
                statistics.add(actual);
            }
            Layer integrity = target.ExecuteSQL("PRAGMA quick_check(1)");
            try {
                Feature row = integrity.GetNextFeature();
                try {
                    if (row == null || !"ok".equals(row.GetFieldAsString(0))) throw new IOException("工作包完整性校验失败");
                } finally { if (row != null) row.delete(); }
            } finally { target.ReleaseResultSet(integrity); }
            return result;
        } finally { target.delete(); srs.delete(); }
    }

    private static JsonNode policyGroup(GisVectorLayer layer, JsonNode definition) {
        String key = null;
        for (JsonNode group : definition.path("groups"))
            if (group.path("id").asLong() == layer.getGroupId()) { key = group.path("groupKey").asText(); break; }
        for (JsonNode group : definition.path("tilePolicy").path("groups"))
            if (group.path("groupKey").asText().equals(key)) return group;
        throw new IllegalArgumentException("图层缺少瓦片分类规则: " + layer.getId());
    }

    private static int[] displayLevels(GisVectorLayer layer, JsonNode definition) {
        JsonNode group = policyGroup(layer, definition);
        if ("POINT".equals(layer.getGeometryType()) || group.path("preserveBoundary").asBoolean()) return new int[]{18};
        return java.util.Arrays.stream(DISPLAY_LEVELS)
                .filter(level -> VectorTemplateRules.tileBusinessZoom(level, definition.path("tilePolicy")) >= group.path("minZoom").asDouble())
                .toArray();
    }

    private static void createDisplayLayers(DataSource data, GisVectorLayer layer, JsonNode definition) {
        SpatialReference srs = new SpatialReference();
        srs.ImportFromEPSG(3857);
        try {
            for (int level : displayLevels(layer, definition)) {
                Layer display = data.CreateLayer("d_" + layer.getId() + "_" + level, srs, ogr.wkbUnknown,
                        new Vector<>(List.of("SPATIAL_INDEX=YES", "GEOMETRY_NAME=geom", "FID=fid")));
                for (String name : List.of("attributes", "area", "length", "min_zoom", "anchor_x", "anchor_y")) {
                    FieldDefn field = new FieldDefn(name, name.equals("attributes") ? ogr.OFTString : ogr.OFTReal);
                    try { check(display.CreateField(field)); } finally { field.delete(); }
                }
            }
        } finally { srs.delete(); }
    }

    private static void appendDisplay(DataSource data, VectorTileEncoder encoder, GisVectorFeature feature,
                                      GisVectorLayer layer, JsonNode definition, ObjectMapper json) {
        JsonNode policy = definition.path("tilePolicy");
        var prepared = encoder.prepareDisplay(feature, layer, policyGroup(layer, definition), policy);
        if (prepared.geometry().isEmpty()) return;
        var writer = new org.locationtech.jts.io.WKBWriter(2);
        for (int level : displayLevels(layer, definition)) {
            if (level < 18 && !policyGroup(layer, definition).path("preserveBoundary").asBoolean()) {
                if (VectorTemplateRules.tileBusinessZoom(level, policy) < prepared.minZoom()) continue;
                double pixel = VectorTileEncoder.WORLD_SIZE / Math.scalb(1, level) / policy.path("tileSize").asInt(512);
                if (prepared.geometry().getDimension() == 2 && prepared.area() < policy.path("polygonMinAreaPixels").asDouble() * pixel * pixel) continue;
                if (prepared.geometry().getDimension() == 1 && prepared.length() < policy.path("lineMinLengthPixels").asDouble() * pixel) continue;
            }
            Layer output = data.GetLayerByName("d_" + layer.getId() + "_" + level);
            Feature row = new Feature(output.GetLayerDefn());
            Geometry shape = ogr.CreateGeometryFromWkb(writer.write(encoder.displayLevel(prepared, level, policy)));
            try {
                check(row.SetFID(feature.getId()));
                check(row.SetGeometry(shape));
                row.SetField("attributes", json.writeValueAsString(prepared.properties()));
                row.SetField("area", prepared.area()); row.SetField("length", prepared.length());
                row.SetField("min_zoom", prepared.minZoom());
                if (prepared.anchor() != null) {
                    row.SetField("anchor_x", prepared.anchor().x); row.SetField("anchor_y", prepared.anchor().y);
                }
                check(output.CreateFeature(row));
            } finally { shape.delete(); row.delete(); }
        }
    }

    private static ObjectNode layerStatistics(DataSource data, long id, ObjectMapper json) {
        ObjectNode result = json.createObjectNode().put("id", Long.toString(id));
        Layer rows = data.ExecuteSQL("SELECT COUNT(*) AS total,MIN(minx) AS west,MIN(miny) AS south,MAX(maxx) AS east,MAX(maxy) AS north FROM rtree_l_" + id + "_geom");
        try {
            Feature row = rows.GetNextFeature();
            try {
                long count = row.GetFieldAsInteger64("total");
                result.put("count", count);
                if (count > 0) {
                    double west = row.GetFieldAsDouble("west"), east = row.GetFieldAsDouble("east");
                    double south = row.GetFieldAsDouble("south"), north = row.GetFieldAsDouble("north");
                    org.locationtech.jts.geom.Geometry shape = new org.locationtech.jts.geom.GeometryFactory().toGeometry(
                            new org.locationtech.jts.geom.Envelope(west, east, south, north));
                    Geometry extent = ogr.CreateGeometryFromWkb(new org.locationtech.jts.io.WKBWriter().write(shape));
                    try { result.set("extent", json.readTree(extent.ExportToJson())); } finally { extent.delete(); }
                }
            } finally { row.delete(); }
        } finally { data.ReleaseResultSet(rows); }
        var types = result.putArray("types");
        rows = data.ExecuteSQL("SELECT DISTINCT geometry_type FROM l_" + id + " ORDER BY geometry_type");
        try {
            Feature row;
            while ((row = rows.GetNextFeature()) != null) {
                try { types.add(row.GetFieldAsString("geometry_type")); } finally { row.delete(); }
            }
        } finally { data.ReleaseResultSet(rows); }
        return result;
    }

    private static ObjectNode editNative(JsonNode request, ObjectMapper json) throws IOException {
        DataSource data = ogr.Open(request.path("input").asText(), 1);
        if (data == null) throw new IOException("工作包无法写入");
        try {
            long operation = request.path("operationId").asLong();
            if (operation < 1) throw new IllegalArgumentException("编辑操作编号无效");
            check(data.StartTransaction());
            Layer journal = data.ExecuteSQL("SELECT result FROM ocean_edit_journal WHERE operation_id=" + operation);
            try {
                Feature previous = journal.GetNextFeature();
                if (previous != null) {
                    try { return (ObjectNode) json.readTree(previous.GetFieldAsString("result")); }
                    finally { previous.delete(); }
                }
            } finally { data.ReleaseResultSet(journal); }
            if (request.has("refreshLayerId")) {
                long layerId = request.path("refreshLayerId").asLong();
                JsonNode definition = request.path("definition");
                GisVectorLayer layer = null;
                for (JsonNode node : definition.path("layers")) if (node.path("id").asLong() == layerId) { layer = json.treeToValue(node, GisVectorLayer.class); break; }
                if (layer == null) throw new IOException("待重建图层定义缺失");
                for (int i = data.GetLayerCount() - 1; i >= 0; i--)
                    if (data.GetLayer(i).GetName().startsWith("d_" + layerId + "_")) check(data.DeleteLayer(i));
                createDisplayLayers(data, layer, definition);
                Layer rows = data.ExecuteSQL("SELECT feature_json FROM l_" + layerId + " ORDER BY fid");
                var encoder = encoder(json);
                try {
                    Feature row;
                    while ((row = rows.GetNextFeature()) != null) {
                        try { appendDisplay(data, encoder, json.readValue(row.GetFieldAsString("feature_json"), GisVectorFeature.class), layer, definition, json); }
                        finally { row.delete(); }
                    }
                } finally { data.ReleaseResultSet(rows); }
                ObjectNode result = json.createObjectNode();
                result.putArray("layers").add(layerStatistics(data, layerId, json));
                commitEdit(data, operation, result, json);
                return result;
            }
            long sourceId = request.path("sourceLayerId").asLong();
            long fid = request.path("featureId").asLong();
            Layer source = data.GetLayerByName("l_" + sourceId);
            if (source == null) throw new IOException("待编辑图层不存在");
            Feature previous = source.GetFeature(fid);
            long expected = request.path("expectedVersion").asLong();
            if (expected == 0 && previous != null || expected > 0 && previous == null)
                throw new IOException("工作包要素版本冲突");
            if (previous != null) {
                try {
                    if (json.readTree(previous.GetFieldAsString("feature_json")).path("version").asLong() != expected)
                        throw new IOException("工作包要素版本冲突");
                } finally { previous.delete(); }
                check(source.DeleteFeature(fid));
                for (int level : DISPLAY_LEVELS) {
                    String table = "d_" + sourceId + "_" + level;
                    if (data.GetLayerByName(table) != null) execute(data, "DELETE FROM " + table + " WHERE fid=" + fid);
                }
            }
            JsonNode definition = request.path("definition");
            long targetId = sourceId;
            if (!request.path("delete").asBoolean()) {
                GisVectorFeature feature = json.treeToValue(request.path("feature"), GisVectorFeature.class);
                targetId = feature.getLayerId();
                GisVectorLayer layer = null;
                for (JsonNode node : definition.path("layers")) if (node.path("id").asLong() == targetId) { layer = json.treeToValue(node, GisVectorLayer.class); break; }
                if (layer == null) throw new IOException("目标图层定义缺失");
                Layer target = data.GetLayerByName("l_" + targetId);
                if (target == null) {
                    SpatialReference srs = new SpatialReference(); srs.ImportFromEPSG(4326);
                    try { target = createLayer(data, "l_" + targetId, srs); } finally { srs.delete(); }
                    createDisplayLayers(data, layer, definition);
                    execute(data, "CREATE INDEX idx_l_" + targetId + "_kind ON l_" + targetId + "(geometry_type)");
                }
                append(target, fid, request.path("feature"), json.readTree(feature.getGeometryJson()), json);
                appendDisplay(data, encoder(json), feature, layer, definition, json);
            }
            ObjectNode result = json.createObjectNode();
            var statistics = result.putArray("layers");
            statistics.add(layerStatistics(data, sourceId, json));
            if (targetId != sourceId) statistics.add(layerStatistics(data, targetId, json));
            commitEdit(data, operation, result, json);
            return result;
        } catch (RuntimeException | IOException ex) {
            data.RollbackTransaction();
            throw ex;
        } finally { data.delete(); }
    }

    private static void execute(DataSource data, String sql) {
        org.gdal.gdal.gdal.ErrorReset();
        Layer rows = data.ExecuteSQL(sql);
        if (rows != null) data.ReleaseResultSet(rows);
        if (org.gdal.gdal.gdal.GetLastErrorType() >= 3) {
            String message = org.gdal.gdal.gdal.GetLastErrorMsg();
            org.gdal.gdal.gdal.ErrorReset();
            throw new IllegalStateException(message);
        }
    }

    private static void commitEdit(DataSource data, long operation, ObjectNode result, ObjectMapper json) {
        execute(data, "INSERT INTO ocean_edit_journal(operation_id,result) VALUES(" + operation + ",'" +
                json.writeValueAsString(result).replace("'", "''") + "')");
        check(data.CommitTransaction());
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
