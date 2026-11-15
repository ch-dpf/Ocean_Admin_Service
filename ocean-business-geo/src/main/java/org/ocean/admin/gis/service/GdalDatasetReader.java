package org.ocean.admin.gis.service;

import org.gdal.gdal.Dataset;
import org.gdal.gdal.InfoOptions;
import org.gdal.gdal.ProgressCallback;
import org.gdal.gdal.TranslateOptions;
import org.gdal.gdal.VectorTranslateOptions;
import org.gdal.gdal.WarpOptions;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconstConstants;
import org.gdal.ogr.Layer;
import org.gdal.osr.SpatialReference;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.ImageryProcessingParameters;
import org.ocean.admin.gis.dto.TerrainProcessingParameters;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Vector;
import java.util.jar.JarFile;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在独立 Java 进程内调用 GDAL JNI，并为现有流式解析器准备临时标准数据。 */
@Component
public class GdalDatasetReader {
    private final GisProcessingProperties properties;
    private final GisStorageService storage;
    private final ControlledWorkspaceResolver sources;
    private final ObjectMapper json;
    private final GdalTileWorkerPool tileWorkers;
    private final GdalTileWorkerPool mapWorkers;

    public GdalDatasetReader(GisStorageService storage, GisProcessingProperties properties, ControlledWorkspaceResolver sources, ObjectMapper json,
                             GdalTileWorkerPool tileWorkers, org.ocean.admin.gis.config.VectorTileProperties limits) {
        this.storage = storage;
        this.properties = properties;
        this.sources = sources;
        this.json = json;
        this.tileWorkers = tileWorkers;
        this.mapWorkers = new GdalTileWorkerPool(storage, properties, limits, json, 2);
    }

    @jakarta.annotation.PreDestroy
    public void closeMapWorkers() { mapWorkers.close(); }

    public record DriverCapability(boolean available, String reason) { }

    /** 处理准备契约在提交和执行阶段共用，避免已知的输入问题进入切片队列。 */
    public record RasterPlan(String purpose, String targetCrs, List<Integer> bands,
                             List<Double> displayRange, double elevationScale, double elevationOffset,
                             String resampling, boolean palette) {
        /** 自动亮度统计只针对数值颜色通道，透明度继续按满量程转换。 */
        private boolean needsDisplayRange(JsonNode info) {
            if (!"IMAGERY".equals(purpose) || palette || displayRange != null) return false;
            int colors = bands.size() == 2 || bands.size() == 4 ? bands.size() - 1 : bands.size();
            for (int index = 0; index < colors; index++) {
                if (!"Byte".equals(info.path("bands").get(bands.get(index) - 1).path("type").asText())) return true;
            }
            return false;
        }
    }

    public static RasterPlan planRaster(JsonNode info, GisProcessingParameters parameters) {
        JsonNode sourceBands = info.path("bands");
        if (!sourceBands.isArray() || sourceBands.isEmpty())
            throw new IllegalArgumentException("栅格资源缺少波段描述，请在文件组中重新解析，完成后再次切片，无需重新上传");
        boolean terrain = parameters instanceof TerrainProcessingParameters;
        if (!terrain && !(parameters instanceof ImageryProcessingParameters))
            throw new IllegalArgumentException("栅格只能生成影像或地形输入");
        var selection = parameters.raster();
        List<Integer> selected = selection == null ? null : selection.bands();
        if (selected == null) {
            if (sourceBands.size() == 1) selected = List.of(1);
            else if (!terrain) {
                List<Integer> colors = new ArrayList<>();
                for (String color : List.of("Red", "Green", "Blue", "Alpha")) {
                    List<Integer> matches = new ArrayList<>();
                    for (int index = 0; index < sourceBands.size(); index++) {
                        if (color.equals(sourceBands.get(index).path("colorInterpretation").asText())) matches.add(index + 1);
                    }
                    if (matches.size() == 1) colors.add(matches.getFirst());
                    else if (!color.equals("Alpha") || !matches.isEmpty()) {
                        colors.clear();
                        break;
                    }
                }
                if (colors.size() == 3 || colors.size() == 4) selected = List.copyOf(colors);
                else if (sourceBands.size() == 2
                        && "Gray".equals(sourceBands.get(0).path("colorInterpretation").asText())
                        && "Alpha".equals(sourceBands.get(1).path("colorInterpretation").asText())) selected = List.of(1, 2);
            }
        }
        if (selected == null) throw new IllegalArgumentException(terrain
                ? "数据包含多个波段，请明确选择一个高程波段"
                : "数据没有明确的颜色组合，请选择灰度波段或RGB波段组合");
        if (terrain && selected.size() != 1) throw new IllegalArgumentException("地形处理必须选择一个高程波段");
        for (int band : selected) {
            if (band > sourceBands.size()) throw new IllegalArgumentException("选择的波段不存在: " + band);
            if (sourceBands.get(band - 1).path("type").asText().startsWith("C"))
                throw new IllegalArgumentException("复数波段须明确转换为实数物理量后处理");
        }
        List<Double> range = selection == null ? null : selection.displayRange();
        boolean palette = selected.size() == 1
                && "Palette".equals(sourceBands.get(selected.getFirst() - 1).path("colorInterpretation").asText());
        double scale = 1;
        double offset = 0;
        if (terrain) {
            JsonNode band = sourceBands.get(selected.getFirst() - 1);
            String unit = band.path("unit").asText("").trim().toLowerCase(Locale.ROOT);
            if (selection != null && selection.elevationScale() != null) scale = selection.elevationScale();
            // 地形输入未标注单位时按米解释，前端无需补填；已标注单位仍按实际含义换算。
            else if (unit.isEmpty() || Set.of("m", "meter", "meters", "metre", "metres").contains(unit)) scale = 1;
            else if (Set.of("ft", "foot", "feet").contains(unit)) scale = 0.3048;
            else throw new IllegalArgumentException("不支持源数据标注的高程单位: " + unit);
            offset = band.path("offset").asDouble(0) * scale
                    + (selection == null || selection.elevationOffset() == null ? 0 : selection.elevationOffset());
            scale *= band.path("scale").asDouble(1);
            if (!Double.isFinite(scale) || !Double.isFinite(offset) || scale == 0)
                throw new IllegalArgumentException("高程波段比例或偏移无效");
        }
        String target = parameters.targetCrs();
        if (!Set.of("EPSG:4326", "EPSG:3857").contains(target) || terrain && !"EPSG:4326".equals(target))
            throw new IllegalArgumentException("处理目标坐标系不受支持");
        String resampling = terrain ? ((TerrainProcessingParameters) parameters).interpolationType()
                : ((ImageryProcessingParameters) parameters).resampling();
        if (!Set.of("NEAREST", "BILINEAR").contains(resampling.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("重采样算法必须为NEAREST或BILINEAR");
        return new RasterPlan(terrain ? "TERRAIN" : "IMAGERY", target, List.copyOf(selected), range, scale, offset,
                "NEAREST".equalsIgnoreCase(resampling) ? "near" : "bilinear", palette);
    }

    /** 在读取所用的同一隔离环境探测输入及中间产物驱动，结果交由能力服务缓存。 */
    public java.util.Map<GisFileFormat, DriverCapability> probeCapabilities() {
        try (var context = new ReadContext(() -> { })) {
            context.timeout = Duration.ofSeconds(15);
            Path requestFile = context.directory().resolve("request.json");
            ObjectNode request = json.createObjectNode().put("operation", "CAPABILITIES")
                    .put("version", properties.getGdalVersion()).put("maxMetadataBytes", properties.getGdalMaxMetadataBytes());
            json.writeValue(requestFile.toFile(), request);
            run(context, workerCommand(requestFile), stage -> { });
            Path resultFile = context.directory().resolve("result.json");
            if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > properties.getGdalMaxMetadataBytes())
                throw failure("INVALID_NATIVE_METADATA", "格式能力探测未返回有效结果");
            JsonNode result = json.readTree(resultFile.toFile());
            if (result.has("error")) throw failure("GDAL_UNAVAILABLE", result.path("error").path("message").asText());
            var capabilities = new java.util.EnumMap<GisFileFormat, DriverCapability>(GisFileFormat.class);
            for (GisFileFormat format : GisFileFormat.values()) {
                JsonNode item = result.path(format.name());
                if (!item.path("available").isBoolean()) throw failure("INVALID_NATIVE_METADATA", "格式能力探测结果不完整");
                boolean available = item.path("available").asBoolean();
                String reason = available ? null : item.path("reason").asText("读取驱动不可用");
                capabilities.put(format, new DriverCapability(available, reason));
            }
            return java.util.Map.copyOf(capabilities);
        } catch (ReaderException ex) { throw ex; }
        catch (Exception ex) { throw failure("GDAL_UNAVAILABLE", "格式能力探测失败: " + ex.getMessage()); }
    }

    /** 原文件始终只读；临时文件随上下文关闭删除，提交前须再次核验所有源。 */
    public ReadContext prepare(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                               Consumer<ProgressStage> progress) {
        return prepare(group, members, heartbeat, progress, false, null);
    }

    /** 准备任务来源；自动影像先统计范围，由completeProcessing汇总后生成引擎输入。 */
    public ReadContext prepareProcessing(GisFileGroup group, List<GisFileMeta> members,
            GisProcessingParameters parameters, Runnable heartbeat, Consumer<ProgressStage> progress) {
        return prepare(group, members, heartbeat, progress, false, parameters);
    }

    /** 同一任务先汇总所有来源的有效数值范围，再统一转换，避免按文件拉伸产生接缝。 */
    public void completeProcessing(List<ReadContext> contexts, Consumer<ProgressStage> progress) {
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (ReadContext context : contexts) {
            if (context.displayRange == null) continue;
            minimum = Math.min(minimum, context.displayRange.get(0));
            maximum = Math.max(maximum, context.displayRange.get(1));
        }
        List<Double> range = null;
        if (Double.isFinite(minimum)) {
            if (minimum == maximum) {
                double padding = Math.max(1, Math.abs(minimum) * 0.01);
                minimum -= padding;
                maximum += padding;
            }
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || !Double.isFinite(maximum - minimum))
                throw new IllegalArgumentException("影像数值范围过大，无法自动转换为显示亮度");
            range = List.of(minimum, maximum);
        }
        for (ReadContext context : contexts) {
            if (context.processingRequest == null) continue;
            context.verifySources();
            ObjectNode request = context.processingRequest.deepCopy();
            request.remove("operation");
            if (range != null) request.set("automaticDisplayRange", json.valueToTree(range));
            try {
                readWithWorker(context, request, progress);
                context.verifySources();
                context.processingRequest = null;
            } catch (ReaderException ex) { throw ex; }
            catch (Exception ex) { throw failure("NATIVE_READ_FAILED", "自动影像转换失败: " + ex.getMessage()); }
        }
    }

    /** 原始快照与制图转换独立执行，空层或无效制图数据也可留存供检查。 */
    public ReadContext prepareSnapshot(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                                       Consumer<ProgressStage> progress) {
        return prepare(group, members, heartbeat, progress, true, null);
    }

    private ReadContext prepare(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                                Consumer<ProgressStage> progress, boolean snapshotOnly, GisProcessingParameters parameters) {
        return prepare(group, members, heartbeat, progress, snapshotOnly, parameters, false);
    }

    /** 只准备受控成员及描述信息，预览不生成全幅标准化栅格。 */
    public ReadContext preparePreview(GisFileGroup group, List<GisFileMeta> members) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        var preparing = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            return prepare(group, members, () -> {
                if (Thread.currentThread().isInterrupted() || preparing.get() && System.nanoTime() > deadline)
                    throw failure("PREVIEW_TIMEOUT", "预览来源准备超时，请检查文件大小与存储位置");
            }, stage -> { }, false, null, true);
        } finally { preparing.set(false); }
    }

    private ReadContext prepare(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                                Consumer<ProgressStage> progress, boolean snapshotOnly,
                                GisProcessingParameters parameters, boolean previewOnly) {
        if (group == null || members == null || members.isEmpty()) throw failure("MISSING_GROUP", "文件组没有可读取成员");
        GisFileFormat format;
        try { format = GisFileFormat.valueOf(group.getFormat()); }
        catch (RuntimeException ex) { throw failure("UNSUPPORTED_FORMAT", "文件组格式未被支持"); }
        if (snapshotOnly && !format.isVector()) throw failure("UNSUPPORTED_FORMAT", "原始要素快照只支持矢量文件组");
        ReadContext context = new ReadContext(heartbeat);
        try {
            JsonNode options = readOptions(group.getReadOptionsJson());
            GisFileMeta entry = null;
            Set<String> paths = new HashSet<>();
            for (GisFileMeta member : members) {
                Path relative = memberPath(member.getMemberPath());
                if (!paths.add(relative.toString().toLowerCase(Locale.ROOT))) throw failure("AMBIGUOUS_MEMBERS", "文件组存在重名路径");
                Path source = sources.resolveFile(member).toAbsolutePath().normalize();
                BasicFileAttributes stat = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                context.snapshots.add(new SourceSnapshot(member, source, stat.size(), stat.lastModifiedTime().toMillis(), stat.fileKey()));
                if (member.getId() != null && member.getId().equals(group.getEntryFileId())) entry = member;
            }
            if (entry == null) throw failure("MISSING_ENTRY", "文件组入口文件不存在");
            if (GisFileFormat.fromFileName(entry.getMemberPath()) != format) throw failure("FORMAT_MISMATCH", "入口扩展名与文件组格式不一致");
            Set<String> extensions = new HashSet<>();
            for (GisFileMeta member : members) extensions.add(GisFileFormat.extension(member.getMemberPath()));
            boolean zeroColumnMif = format == GisFileFormat.MIF && context.snapshots.stream()
                    .filter(snapshot -> snapshot.member().getId().equals(group.getEntryFileId()))
                    .anyMatch(snapshot -> zeroColumnMif(snapshot.path()));
            if (!zeroColumnMif && !extensions.containsAll(format.requiredExtensions())) throw failure("MISSING_REQUIRED_MEMBERS", "文件组缺少必要配套文件: " + format.requiredExtensions());
            context.verifySources();
            Path entryPath;
            if (!snapshotOnly && !previewOnly && format == GisFileFormat.GEOJSON && members.size() == 1) {
                entryPath = context.snapshots.getFirst().path();
            } else {
                Path staged = context.directory().resolve("source");
                Files.createDirectory(staged);
                long totalBytes = context.snapshots.stream().mapToLong(SourceSnapshot::size).reduce(0L, Math::addExact);
                long preparedBytes = 0;
                Consumer<ProgressStage> preparationProgress = ProgressStage.throttled(progress);
                preparationProgress.accept(totalBytes == 0 ? ProgressStage.indeterminate("SOURCE_PREPARING", "准备源文件")
                        : ProgressStage.determinate("SOURCE_PREPARING", "准备源文件", 0, totalBytes, ProgressUnit.BYTE));
                for (SourceSnapshot snapshot : context.snapshots) {
                    Path target = staged.resolve(memberPath(snapshot.member().getMemberPath())).normalize();
                    if (!target.startsWith(staged)) throw failure("INVALID_MEMBER_PATH", "文件组路径越界");
                    Files.createDirectories(target.getParent());
                    heartbeat.run();
                    // 只暴露已登记成员；硬链接不暴露原目录，避免复制大文件。
                    try {
                        Files.createLink(target, snapshot.path());
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                            Files.deleteIfExists(target);
                            throw failure("SOURCE_CHANGED", "源文件已变为链接或特殊文件");
                        }
                        preparedBytes += snapshot.size();
                        continue;
                    } catch (IOException | UnsupportedOperationException | SecurityException linkFailure) {
                        // 不同文件系统或不允许硬链接时，按原有受控流式复制准备副本。
                    }
                    try (InputStream input = Files.newInputStream(snapshot.path(), LinkOption.NOFOLLOW_LINKS); OutputStream output = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[1024 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            heartbeat.run();
                            output.write(buffer, 0, count);
                            preparedBytes += count;
                            if (preparedBytes > totalBytes) throw failure("SOURCE_CHANGED", "准备期间源文件大小发生变化");
                            if (preparedBytes < totalBytes)
                                preparationProgress.accept(ProgressStage.determinate("SOURCE_PREPARING",
                                        "准备源文件: " + snapshot.member().getMemberPath(), preparedBytes, totalBytes, ProgressUnit.BYTE));
                        }
                    }
                }
                if (totalBytes > 0)
                    preparationProgress.accept(ProgressStage.determinate("SOURCE_PREPARING", "源文件准备完成",
                            preparedBytes, totalBytes, ProgressUnit.BYTE));
                entryPath = staged.resolve(memberPath(entry.getMemberPath()));
                for (GisFileMeta member : members) {
                    if (GisFileFormat.extension(member.getMemberPath()).equals("tab")) {
                        validateTabReferences(staged.resolve(memberPath(member.getMemberPath())), staged, paths);
                    }
                }
            }
            context.verifySources();
            if (previewOnly) {
                context.previewRequest = json.createObjectNode().put("operation", "PREVIEW")
                        .put("input", entryPath.toString()).put("format", format.name());
                context.previewRequest.set("options", options);
                context.packageResult = context.preview(json.createObjectNode().put("action", "DESCRIBE"));
                // 浏览器可直接显示的普通图片保留原始颜色、方向和GIF帧；大图使用有界渲染。
                if (Set.of(GisFileFormat.PNG, GisFileFormat.JPG, GisFileFormat.GIF).contains(format)) {
                    JsonNode layer = context.packageResult.path("layers").path(0);
                    long pixels = layer.path("width").asLong() * layer.path("height").asLong();
                    if (layer.isObject() && pixels > 0 && pixels <= 32_000_000 && Files.size(entryPath) <= 20 * 1024 * 1024)
                        ((ObjectNode) layer).put("nativeImage", true);
                }
            } else if (snapshotOnly) {
                ObjectNode request = json.createObjectNode().put("packageOperation", "SNAPSHOT")
                        .put("input", entryPath.toString()).put("format", format.name());
                request.set("options", options);
                context.packageResult = executePackage(context, request, progress);
            } else if (format == GisFileFormat.GEOJSON) {
                context.vectors.add(new VectorSource("default", entry.getOriginalName(), entryPath, "EPSG:4326", null, null));
            } else {
                progress.accept(ProgressStage.indeterminate("NATIVE_INSPECTION", "检查原始驱动、坐标系和数据结构"));
                prepareWithWorker(context, format, entryPath, entry.getOriginalName(), options, parameters, progress);
            }
            context.verifySources();
            return context;
        } catch (RuntimeException ex) {
            try { context.close(); }
            catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        } catch (Exception ex) {
            try { context.close(); }
            catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            throw failure("SOURCE_READ_FAILED", "无法准备原始数据源: " + ex.getMessage());
        }
    }

    /** 复用受限工作进程执行托管包操作，JNI 不进入服务 JVM。 */
    public JsonNode executePackage(ObjectNode request, Runnable heartbeat) {
        if ("WMS_MAP".equals(request.path("packageOperation").asText())) {
            return mapWorkers.execute(request, path -> {
                try { return workerCommand(path); }
                catch (IOException ex) { throw new IllegalStateException("无法构造地图工作进程", ex); }
            });
        }
        if (Set.of("QUERY", "TILE", "WINDOW").contains(request.path("packageOperation").asText())) {
            return tileWorkers.execute(request, path -> {
                try { return workerCommand(path); }
                catch (IOException ex) { throw new IllegalStateException("无法构造瓦片工作进程", ex); }
            });
        }
        return executePackage(request, heartbeat, stage -> { });
    }

    /** 离线绘图使用独立进程，并把分块完成进度接入现有任务通道。 */
    public JsonNode executePackage(ObjectNode request, Runnable heartbeat, Consumer<ProgressStage> progress) {
        try (var context = new ReadContext(heartbeat)) {
            if ("RASTER_TILES".equals(request.path("packageOperation").asText()))
                context.timeout = Duration.ofSeconds(request.path("timeoutSeconds").asLong());
            return executePackage(context, request, progress);
        } catch (IOException ex) { throw failure("PACKAGE_IO", "矢量包操作失败: " + ex.getMessage()); }
    }

    private JsonNode executePackage(ReadContext context, ObjectNode request, Consumer<ProgressStage> progress) throws IOException {
        try {
            request.put("version", properties.getGdalVersion());
            request.put("maxMetadataBytes", Math.max(properties.getGdalMaxMetadataBytes(), 16 * 1024 * 1024));
            if (Set.of("QUERY", "TILE", "WINDOW", "SOURCE_PREVIEW").contains(
                    request.path("packageOperation").asText())) {
                context.timeout = Duration.ofSeconds(30);
            }
            if (request.hasNonNull("output")) context.packageOutput = Path.of(request.path("output").asText());
            Path requestFile = context.directory().resolve("request.json");
            json.writeValue(requestFile.toFile(), request);
            run(context, workerCommand(requestFile), progress);
            Path resultFile = context.directory().resolve("result.json");
            if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > 16 * 1024 * 1024)
                throw failure("PACKAGE_IO", "矢量包结果缺失或超过限额");
            JsonNode result = json.readTree(resultFile.toFile());
            if (result.has("error")) throw failure("PACKAGE_IO", result.path("error").path("message").asText());
            return result;
        } catch (IOException ex) { throw ex; }
        catch (Exception ex) { throw failure("PACKAGE_IO", ex.getMessage()); }
    }

    /** 主进程只传递受控文件组参数，不在服务 JVM 中加载 JNI。 */
    private void prepareWithWorker(ReadContext context, GisFileFormat format, Path input, String sourceName,
                                   JsonNode options, GisProcessingParameters parameters, Consumer<ProgressStage> progress) throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("format", format.name());
        request.put("input", input.toString());
        request.put("sourceName", sourceName);
        request.set("options", options);
        if (parameters != null) {
            request.put("processingType", parameters.processingType().name());
            request.set("parameters", json.valueToTree(parameters));
            if (parameters instanceof ImageryProcessingParameters
                    && (parameters.raster() == null || parameters.raster().displayRange() == null)) {
                request.put("operation", "RASTER_STATISTICS");
                context.processingRequest = request;
            }
        }
        request.put("version", properties.getGdalVersion());
        request.put("maxMetadataBytes", properties.getGdalMaxMetadataBytes());
        readWithWorker(context, request, progress);
    }

    private void readWithWorker(ReadContext context, ObjectNode request, Consumer<ProgressStage> progress) throws Exception {
        Path directory = context.directory();
        Path requestFile = directory.resolve("request.json");
        json.writeValue(requestFile.toFile(), request);
        run(context, workerCommand(requestFile), progress);
        Path resultFile = directory.resolve("result.json");
        if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > properties.getGdalMaxMetadataBytes())
            throw failure("INVALID_NATIVE_METADATA", "GDAL 工作进程没有返回有限额的读取结果");
        JsonNode result = json.readTree(resultFile.toFile());
        JsonNode error = result.path("error");
        if (error.isObject()) throw failure(error.path("code").asString("NATIVE_READ_FAILED"),
                error.path("message").asString("GDAL Java 读取失败"));
        if (!result.path("vectors").isArray() || !result.path("rasters").isArray())
            throw failure("INVALID_NATIVE_METADATA", "GDAL 工作进程结果结构无效");
        if ("RASTER_STATISTICS".equals(request.path("operation").asText())) {
            JsonNode range = result.path("displayRange");
            if (!range.isMissingNode()) {
                if (!range.isArray() || range.size() != 2 || !range.get(0).isNumber() || !range.get(1).isNumber()
                        || !Double.isFinite(range.get(0).asDouble()) || !Double.isFinite(range.get(1).asDouble())
                        || range.get(0).asDouble() > range.get(1).asDouble())
                    throw failure("INVALID_NATIVE_METADATA", "自动显示范围统计无效");
                context.displayRange = List.of(range.get(0).asDouble(), range.get(1).asDouble());
            }
            return;
        }
        for (JsonNode source : result.path("vectors")) {
            context.vectors.add(new VectorSource(source.path("key").asString(), source.path("name").asString(),
                    resultPath(directory, source), source.path("sourceCrs").asString(null), source.get("sourceExtent"), source.get("metadata")));
        }
        for (JsonNode source : result.path("rasters")) {
            Path rasterPath = resultPath(directory, source);
            JsonNode footprint = source.path("metadata").path("footprintFile");
            Path footprintPath = footprint.isString()
                    ? resultPath(directory, json.createObjectNode().put("path", footprint.asText())) : rasterPath;
            context.rasters.add(new RasterSource(source.path("key").asString(), source.path("name").asString(),
                    rasterPath, footprintPath, source.path("sourceCrs").asString(null), source.get("sourceExtent"), source.get("metadata")));
        }
        if (context.vectors.isEmpty() && context.rasters.isEmpty()) throw failure("EMPTY_DATASET", "GDAL 工作进程未生成空间数据");
    }

    private static Path resultPath(Path directory, JsonNode source) throws IOException {
        Path output = directory.resolve(memberPath(source.path("path").asString())).normalize();
        if (!output.getParent().equals(directory) || Files.isSymbolicLink(output))
            throw failure("INVALID_NATIVE_METADATA", "GDAL 工作进程输出路径无效");
        requireOutput(output);
        return output;
    }

    /** 普通类路径和 Spring Boot 可执行 JAR 均直接进入 Worker，不初始化 Spring。 */
    private List<String> workerCommand(Path request) throws IOException {
        List<String> command = new ArrayList<>();
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        String heap = properties.getGdalWorkerMaxHeap();
        if (heap == null || !heap.matches("[1-9][0-9]*[mMgG]")) throw failure("INVALID_GDAL_CONFIG", "GDAL 工作进程堆内存配置无效");
        command.add("-Xmx" + heap);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djava.awt.headless=true");
        // GeoTools 的 EPSG 缓存按进程隔离，避免与主服务争用 Windows 文件映射。
        command.add("-Djava.io.tmpdir=" + request.toAbsolutePath().getParent());
        command.add("-XX:-CreateCoredumpOnCrash");
        String nativePath = properties.getGdalNativeLibraryPath();
        if (nativePath != null && !nativePath.isBlank())
            command.add("-Djava.library.path=" + Path.of(nativePath).toAbsolutePath().normalize());
        String worker = Worker.class.getName();
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) throw failure("GDAL_UNAVAILABLE", "无法确定 Java 工作进程类路径");
        List<String> entries = new ArrayList<>();
        Path bootJar = null;
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            Path path = Path.of(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize();
            entries.add(path.toString());
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    if (jar.getJarEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null
                            && jar.getJarEntry("BOOT-INF/classes/") != null) bootJar = path;
                }
            }
        }
        if (bootJar != null) {
            command.add("-Dloader.main=" + worker);
            command.add("-Dloader.config.location=" + request.resolveSibling("no-loader-config.properties").toUri());
            command.addAll(List.of("-cp", bootJar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher"));
        } else command.addAll(List.of("-cp", String.join(File.pathSeparator, entries), worker));
        command.add(request.toString());
        return command;
    }

    private JsonNode readOptions(String value) {
        if (value == null || value.isBlank()) return json.createObjectNode();
        if (value.length() > 65536) throw failure("INVALID_READ_OPTIONS", "读取配置过长");
        try {
            JsonNode options = json.readTree(value);
            if (!options.isObject()) throw failure("INVALID_READ_OPTIONS", "读取配置须为 JSON 对象");
            return options;
        } catch (ReaderException ex) { throw ex; }
        catch (RuntimeException ex) { throw failure("INVALID_READ_OPTIONS", "读取配置不是有效 JSON"); }
    }

    private static String explicitCrs(JsonNode options) {
        if (!options.has("sourceCrs")) return null;
        String value = text(options.get("sourceCrs"));
        if (value == null || value.length() > 32768 || value.indexOf('\0') >= 0
                || !(value.matches("EPSG:[0-9]{1,9}") || value.equals("OGC:CRS84")
                || value.matches("(?s)(GEOGCS|PROJCS|GEOCCS|GEOGCRS|GEODCRS|PROJCRS|BOUNDCRS|COMPOUNDCRS)\\[.*"))) {
            throw failure("INVALID_CRS", "sourceCrs 必须为 EPSG 编码或内联 WKT，禁止 URL 和文件路径");
        }
        return value;
    }

    private static String fieldOption(JsonNode options, String name) {
        String value = text(options.path(name));
        if (value == null || value.length() > 256 || value.indexOf(',') >= 0 || value.chars().anyMatch(c -> c < 32 || c == '*')) {
            throw failure("MISSING_COORDINATE_FIELDS", "CSV 必须明确提供有效的 xField 和 yField");
        }
        return value;
    }

    private static void validateCsvFields(JsonNode layer, JsonNode options) {
        Set<String> names = new HashSet<>();
        for (JsonNode field : layer.path("fields")) names.add(field.path("name").asString());
        String x = fieldOption(options, "xField");
        String y = fieldOption(options, "yField");
        if (x.equals(y) || !names.contains(x) || !names.contains(y)) throw failure("INVALID_COORDINATE_FIELDS", "CSV 配置的坐标字段缺失或重复");
    }

    private static Path memberPath(String value) {
        if (value == null || value.isBlank()) throw failure("MISSING_MEMBER_PATH", "文件缺少经确认的组内相对路径");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.chars().anyMatch(c -> c < 32)) {
            throw failure("INVALID_MEMBER_PATH", "文件组路径必须是安全相对路径");
        }
        Path path = Path.of(normalized);
        for (Path segment : path) {
            if (segment.toString().equals("..") || segment.toString().equals(".")) throw failure("INVALID_MEMBER_PATH", "文件组路径不能包含上级或当前目录");
        }
        if (path.isAbsolute() || path.getFileName() == null) throw failure("INVALID_MEMBER_PATH", "文件组路径无效");
        return path;
    }

    private static boolean zeroColumnMif(Path input) {
        try (InputStream stream = Files.newInputStream(input)) {
            String header = new String(stream.readNBytes(65536), StandardCharsets.ISO_8859_1);
            return Pattern.compile("(?im)^\\s*Columns\\s+0\\s*$").matcher(header).find();
        } catch (IOException ex) { throw failure("SOURCE_READ_FAILED", "无法读取 MIF 结构头"); }
    }

    /** TAB 可在文件内引用其他表文件，引用目标必须确实属于本次组内副本。 */
    private static void validateTabReferences(Path entry, Path staged, Set<String> paths) throws IOException {
        if (Files.size(entry) > 1024 * 1024) throw failure("INVALID_TAB", "TAB 描述文件超过安全读取限额");
        String content = Files.readString(entry, StandardCharsets.ISO_8859_1);
        Matcher references = Pattern.compile("(?im)^\\s*File\\s+\"([^\"]+)\"").matcher(content);
        boolean found = false;
        while (references.find()) {
            found = true;
            Path target = entry.getParent().resolve(memberPath(references.group(1))).normalize();
            if (!target.startsWith(staged) || !paths.contains(staged.relativize(target).toString().toLowerCase(Locale.ROOT))) {
                throw failure("INVALID_TAB_REFERENCE", "TAB 引用了文件组之外的数据文件");
            }
        }
        // 原生二进制表按相同主名隐式定位 DAT/MAP/ID，不包含 File 语句。
        if (!found && !Pattern.compile("(?im)^\\s*Type\\s+NATIVE(?:\\s|$)").matcher(content).find()) {
            throw failure("INVALID_TAB_REFERENCE", "TAB 没有可核验的本地数据文件引用");
        }
    }

    /** 主服务监控 Java 工作进程；原生调用卡死或崩溃不会进入服务 JVM。 */
    private void run(ReadContext context, List<String> command, Consumer<ProgressStage> progress) throws Exception {
        Duration timeout = context.timeout == null ? properties.getGdalProcessTimeout() : context.timeout;
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw failure("INVALID_GDAL_CONFIG", "GDAL 进程超时配置必须大于零");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(context.directory().toFile());
        // PropertiesLauncher 的环境变量优先于系统属性，子进程只允许受控入口和类路径。
        builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("LOADER_")
                || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key.toUpperCase(Locale.ROOT)));
        builder.environment().put("PROJ_NETWORK", "OFF");
        builder.environment().put("CPL_TMPDIR", context.directory().toString());
        builder.environment().put("GDAL_HTTP_TIMEOUT", "1");
        builder.environment().put("CPL_VSIL_CURL_ALLOWED_EXTENSIONS", ".ocean-disabled-network");
        String nativePath = properties.getGdalNativeLibraryPath();
        if (nativePath != null && !nativePath.isBlank()) {
            String absolute = Path.of(nativePath).toAbsolutePath().normalize().toString();
            for (String key : List.of("PATH", "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH"))
                builder.environment().put(key, absolute + File.pathSeparator + builder.environment().getOrDefault(key, ""));
        }
        Process process;
        try { process = builder.start(); }
        catch (IOException ex) { throw failure("GDAL_UNAVAILABLE", "无法启动 GDAL Java 工作进程: " + ex.getMessage()); }
        CapturedStream stdout = new CapturedStream(process.getInputStream(), properties.getGdalMaxLogBytes(), json);
        CapturedStream stderr = new CapturedStream(process.getErrorStream(), properties.getGdalMaxLogBytes(), null);
        Thread outThread = Thread.ofVirtual().start(stdout);
        Thread errThread = Thread.ofVirtual().start(stderr);
        long started = System.nanoTime();
        ProgressStage reported = null;
        try {
            while (true) {
                context.heartbeat.run();
                ProgressStage current = stdout.stage;
                if (current != null && !current.equals(reported)) { progress.accept(current); reported = current; }
                if (stdout.exceeded || stderr.exceeded) throw failure("GDAL_OUTPUT_LIMIT", "GDAL 日志或进度超过读取限额");
                if (Duration.ofNanos(System.nanoTime() - started).compareTo(timeout) > 0) throw failure("GDAL_TIMEOUT", "GDAL 读取超过配置时限");
                if (context.outputBytes() > properties.getGdalMaxOutputBytes()) throw failure("GDAL_OUTPUT_LIMIT", "临时数据超过输出空间限额");
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) break;
            }
            outThread.join(2000);
            errThread.join(2000);
            if (outThread.isAlive() || errThread.isAlive()) throw failure("GDAL_IO_ERROR", "GDAL 输出流未在进程退出后及时关闭");
            if (stdout.error != null || stderr.error != null) throw failure("GDAL_IO_ERROR", "读取 GDAL 工作进程输出失败");
            if (stdout.exceeded || stderr.exceeded || context.outputBytes() > properties.getGdalMaxOutputBytes())
                throw failure("GDAL_OUTPUT_LIMIT", "GDAL 输出超过配置限额");
            if (stdout.stage != null && !stdout.stage.equals(reported)) progress.accept(stdout.stage);
            if (process.exitValue() != 0) {
                boolean structuredError = false;
                Path result = context.directory().resolve("result.json");
                if (Files.isRegularFile(result) && Files.size(result) <= properties.getGdalMaxMetadataBytes()) {
                    try { structuredError = json.readTree(result.toFile()).path("error").isObject(); }
                    catch (RuntimeException ignored) { }
                }
                // 写完成功结果后仍可能原生崩溃，只有正常退出才允许提交读取成功。
                if (!structuredError) {
                    String detail = stderr.value();
                    throw failure("GDAL_UNAVAILABLE", "GDAL Java 工作进程异常退出（" + process.exitValue() + "）: "
                            + detail.substring(0, Math.min(2000, detail.length())));
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure("GDAL_INTERRUPTED", "GDAL 读取已中断");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            // 等待强制退出时暂存中断标记，清理完成后恢复，避免 Windows 文件句柄尚未释放。
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ex) { interrupted = true; }
                }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { outThread.join(2000); errThread.join(2000); }
                catch (InterruptedException ex) { interrupted = true; }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static String text(JsonNode value) {
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static void requireOutput(Path output) throws IOException {
        if (!Files.isRegularFile(output) || Files.size(output) == 0) throw failure("EMPTY_NATIVE_OUTPUT", "原生驱动没有生成可读取数据");
    }

    private static String stableKey(String prefix, String name) throws Exception {
        return prefix + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)));
    }

    public record VectorSource(String key, String name, Path geoJsonPath, String sourceCrs,
                               JsonNode sourceExtent, JsonNode metadata) { }
    public record RasterSource(String key, String name, Path geoTiffPath, Path footprintPath, String sourceCrs,
                               JsonNode sourceExtent, JsonNode metadata) { }
    private record SourceSnapshot(GisFileMeta member, Path path, long size, long modifiedAt, Object fileKey) { }

    /** 上下文持有临时产物，不持有原文件的写入权限。 */
    public final class ReadContext implements AutoCloseable {
        private final Runnable heartbeat;
        private final List<VectorSource> vectors = new ArrayList<>();
        private final List<RasterSource> rasters = new ArrayList<>();
        private final List<SourceSnapshot> snapshots = new ArrayList<>();
        private Path directory;
        private JsonNode packageResult;
        private Duration timeout;
        private Path packageOutput;
        private ObjectNode previewRequest;
        private ObjectNode processingRequest;
        private List<Double> displayRange;

        private ReadContext(Runnable heartbeat) { this.heartbeat = heartbeat; }
        public List<VectorSource> vectors() { return List.copyOf(vectors); }
        public List<RasterSource> rasters() { return List.copyOf(rasters); }
        public JsonNode packageResult() { return packageResult; }
        public Path snapshotPath() throws IOException { return directory().resolve("snapshot.gpkg"); }

        /** 同一预览会话串行访问请求文件，调用者负责会话锁及全局并发限制。 */
        public JsonNode preview(ObjectNode parameters) throws IOException {
            if (previewRequest == null) throw new IllegalStateException("不是预览读取上下文");
            verifySources();
            ObjectNode request = previewRequest.deepCopy();
            request.set("preview", parameters);
            request.put("version", properties.getGdalVersion());
            request.put("maxMetadataBytes", properties.getGdalMaxMetadataBytes());
            timeout = Duration.ofSeconds(30);
            Path requestFile = directory().resolve("request.json");
            json.writeValue(requestFile.toFile(), request);
            try { run(this, workerCommand(requestFile), stage -> { }); }
            catch (ReaderException ex) { throw ex; }
            catch (Exception ex) { throw failure("PREVIEW_FAILED", "预览工作进程读取失败: " + ex.getMessage()); }
            Path resultFile = directory().resolve("result.json");
            if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > 8 * 1024 * 1024)
                throw failure("PREVIEW_LIMIT", "预览描述超过读取限额");
            JsonNode result = json.readTree(resultFile.toFile());
            if (result.has("error")) throw failure("PREVIEW_FAILED", result.path("error").path("message").asText());
            verifySources();
            return result;
        }

        public byte[] previewImage() throws IOException {
            Path output = directory().resolve("preview.png");
            if (!Files.isRegularFile(output) || Files.size(output) > 20 * 1024 * 1024)
                throw failure("PREVIEW_LIMIT", "预览图像缺失或超过限额");
            return Files.readAllBytes(output);
        }

        public byte[] previewOriginalImage() throws IOException {
            if (previewRequest == null || !Set.of("PNG", "JPG", "GIF").contains(previewRequest.path("format").asText()))
                throw new IllegalStateException("该文件不支持浏览器原生图片查看");
            verifySources();
            Path input = Path.of(previewRequest.path("input").asText()).toAbsolutePath().normalize();
            if (!input.startsWith(directory().resolve("source")) || Files.size(input) > 20 * 1024 * 1024)
                throw failure("PREVIEW_LIMIT", "图片超过直接查看限额");
            return Files.readAllBytes(input);
        }

        private Path directory() throws IOException {
            if (directory == null) {
                Path root = storage.resolveKey("runtime/native-read");
                Files.createDirectories(root);
                directory = Files.createTempDirectory(root, "group-");
            }
            return directory;
        }

        private long outputBytes() throws IOException {
            if (directory == null) return 0;
            long[] total = {0};
            Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    long size = attributes.size();
                    total[0] = Long.MAX_VALUE - total[0] < size ? Long.MAX_VALUE : total[0] + size;
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                    // SQLite提交会移除journal；目录扫描不能把正常提交误报为读取失败。
                    if (error instanceof java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE;
                    throw error;
                }
            });
            if (packageOutput != null && Files.isRegularFile(packageOutput)) {
                long size = Files.size(packageOutput);
                total[0] = Long.MAX_VALUE - total[0] < size ? Long.MAX_VALUE : total[0] + size;
            }
            return total[0];
        }

        public void verifySources() {
            for (SourceSnapshot snapshot : snapshots) {
                heartbeat.run();
                try {
                    Path path = sources.resolveFile(snapshot.member()).toAbsolutePath().normalize();
                    BasicFileAttributes stat = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!path.equals(snapshot.path()) || stat.size() != snapshot.size()
                            || stat.lastModifiedTime().toMillis() != snapshot.modifiedAt()
                            || !java.util.Objects.equals(stat.fileKey(), snapshot.fileKey())) {
                        throw failure("SOURCE_CHANGED", "文件组源文件在读取期间发生变化，请重新接入");
                    }
                } catch (ReaderException ex) { throw ex; }
                catch (Exception ex) { throw failure("SOURCE_CHANGED", "文件组源文件不可读取或已变化，请重新接入"); }
            }
        }

        @Override public void close() {
            if (directory == null) return;
            Path cleanup = directory;
            directory = null;
            try (var paths = Files.walk(cleanup)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ex) {
                throw failure("TEMP_CLEANUP_FAILED", "原生读取临时目录清理失败");
            }
        }
    }

    private static final class CapturedStream implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ObjectMapper json;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private volatile boolean exceeded;
        private volatile IOException error;
        private volatile ProgressStage stage;

        private CapturedStream(InputStream input, int limit, ObjectMapper json) {
            this.input = input; this.limit = limit; this.json = json;
        }
        @Override public void run() {
            try {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (json == null) capture(buffer, 0, count);
                    else for (int index = 0; index < count; index++) {
                        if (buffer[index] == '\n') {
                            if (!readStage()) {
                                byte[] message = line.toByteArray(); capture(message, 0, message.length);
                            }
                            line.reset();
                        } else if (line.size() < 8192) line.write(buffer[index]);
                        else exceeded = true;
                    }
                }
                if (json != null && line.size() > 0 && !readStage()) {
                    byte[] message = line.toByteArray(); capture(message, 0, message.length);
                }
            } catch (IOException ex) { error = ex; }
        }
        private void capture(byte[] source, int offset, int count) {
            int remaining = limit - bytes.size();
            if (remaining < count) exceeded = true;
            if (remaining > 0) bytes.write(source, offset, Math.min(remaining, count));
        }
        private boolean readStage() {
            try {
                JsonNode value = json.readTree(line.toByteArray());
                if (!value.path("event").asString().equals("progress")) return false;
                String code = value.path("code").asString();
                String message = value.path("message").asString();
                stage = value.path("completed").isNumber()
                        ? ProgressStage.determinate(code, message, value.path("completed").asLong(), 1000, ProgressUnit.OPERATION)
                        : ProgressStage.indeterminate(code, message);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        private String value() { return bytes.toString(StandardCharsets.UTF_8); }
    }

    /** 独立工作入口：只初始化 GDAL 和 Jackson，不创建 Spring 上下文。 */
    public static final class Worker {
        private final ObjectMapper json = new ObjectMapper();
        private final Path directory;
        private final int metadataLimit;
        private final ObjectNode result;
        private final Consumer<ProgressStage> progress;
        private boolean statisticsOnly;
        private int selectedRasterCount;
        private List<Double> automaticDisplayRange;

        private Worker(Path directory, int metadataLimit) {
            this.directory = directory; this.metadataLimit = metadataLimit;
            result = json.createObjectNode();
            result.putArray("vectors"); result.putArray("rasters");
            progress = ProgressStage.throttled(stage -> {
                ObjectNode event = json.createObjectNode();
                event.put("event", "progress"); event.put("code", stage.code()); event.put("message", stage.message());
                if (stage.completed() != null) event.put("completed", stage.completed());
                System.out.println(json.writeValueAsString(event));
                System.out.flush();
            });
        }

        public static void main(String[] args) throws IOException {
            // 父进程按 UTF-8 读取协议，显式指定标准流编码，避免 Windows 系统编码破坏中文进度消息。
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
            ObjectMapper mapper = new ObjectMapper();
            if (args.length == 2 && "--serve".equals(args[1])) {
                Path requestFile = Path.of(args[0]).toAbsolutePath().normalize();
                boolean initialized = false;
                try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String command;
                    while ((command = input.readLine()) != null) {
                        if (!"execute".equals(command)) throw new IOException("瓦片工作指令无效");
                        ObjectNode response;
                        try {
                            if (!Files.isRegularFile(requestFile) || Files.isSymbolicLink(requestFile)
                                    || Files.size(requestFile) > 16 * 1024 * 1024) throw new IOException("瓦片请求文件无效");
                            JsonNode request = mapper.readTree(requestFile.toFile());
                            if (!initialized) { initialize(request.path("version").asText()); initialized = true; }
                            if (!Set.of("TILE", "QUERY", "WINDOW", "WMS_MAP").contains(request.path("packageOperation").asText()))
                                throw new IOException("交互工作进程仅允许读取操作");
                            response = GeoPackageStore.executeNative(request, requestFile.getParent(), mapper);
                        } catch (Exception ex) { response = error(mapper, "PACKAGE_IO", ex.getMessage()); }
                        mapper.writeValue(requestFile.resolveSibling("result.json").toFile(), response);
                        System.out.println("complete");
                        System.out.flush();
                    }
                }
                return;
            }
            if (args.length == 2 && args[0].equals("--check")) {
                try {
                    initialize(args[1]);
                    ObjectNode capabilities = mapper.createObjectNode();
                    capabilities.put("version", gdal.VersionInfo("RELEASE_NAME"));
                    for (GisFileFormat format : GisFileFormat.values()) {
                        List<String> available = drivers(format);
                        capabilities.set(format.name(), mapper.valueToTree(available));
                        if (format != GisFileFormat.DWG && available.isEmpty())
                            throw failure("DRIVER_UNAVAILABLE", "缺少部署要求的读取驱动: " + format.name());
                    }
                    outputDriver("GeoJSON"); outputDriver("GTiff"); outputDriver("GPKG");
                    System.out.println(mapper.writeValueAsString(capabilities));
                } catch (Exception | LinkageError ex) {
                    System.err.println("GDAL Java 能力检查失败: " + ex.getMessage()); System.exit(1);
                }
                return;
            }
            if (args.length != 1) throw new IllegalArgumentException("工作进程需要一个受控请求文件");
            Path requestFile = Path.of(args[0]).toAbsolutePath().normalize();
            Path directory = requestFile.getParent();
            ObjectNode response;
            try {
                if (!requestFile.getFileName().toString().equals("request.json") || Files.isSymbolicLink(requestFile)
                        || Files.size(requestFile) > 16 * 1024 * 1024) throw failure("INVALID_READ_OPTIONS", "工作进程请求文件无效");
                JsonNode request = mapper.readTree(requestFile.toFile());
                int limit = request.path("maxMetadataBytes").asInt();
                if (limit < 1024) throw failure("INVALID_GDAL_CONFIG", "GDAL 元数据限额无效");
                Worker worker = new Worker(directory, limit);
                initialize(request.path("version").asString());
                if ("CAPABILITIES".equals(request.path("operation").asText())) {
                    ObjectNode capabilities = mapper.createObjectNode();
                    for (GisFileFormat format : GisFileFormat.values()) {
                        ObjectNode item = capabilities.putObject(format.name());
                        try {
                            if (drivers(format).isEmpty()) throw failure("DRIVER_UNAVAILABLE", "当前 GDAL 缺少 " + format.name() + " 读取驱动");
                            if (format.isVector()) {
                                outputDriver("GeoJSON");
                                outputDriver("GPKG");
                            } else outputDriver("GTiff");
                            item.put("available", true);
                        } catch (ReaderException ex) {
                            item.put("available", false).put("reason", ex.getMessage());
                        }
                    }
                    worker.writeResult(capabilities);
                    return;
                }
                if (request.has("packageOperation")) {
                    worker.writeResult(GeoPackageStore.executeNative(request, directory, mapper));
                    return;
                }
                if ("PREVIEW".equals(request.path("operation").asText())) {
                    worker.writeResult(GdalPreviewRenderer.execute(request, directory, mapper));
                    return;
                }
                GisFileFormat format = GisFileFormat.valueOf(request.path("format").asString());
                Path input = Path.of(request.path("input").asString()).toAbsolutePath().normalize();
                if (!input.startsWith(directory.resolve("source")) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
                    throw failure("INVALID_MEMBER_PATH", "工作进程只能读取受控成员目录");
                JsonNode options = request.path("options");
                if (!options.isObject()) throw failure("INVALID_READ_OPTIONS", "读取配置须为对象");
                List<String> drivers = drivers(format);
                if (drivers.isEmpty()) throw failure("DRIVER_UNAVAILABLE", "当前 GDAL 未安装 " + format.name() + " 读取驱动");
                worker.progress.accept(ProgressStage.indeterminate("NATIVE_INSPECTION", "Java GDAL 检查原始数据结构"));
                if (format.isVector()) worker.vectors(format, input, options, drivers);
                else {
                    GisProcessingParameters parameters = switch (request.path("processingType").asText("")) {
                        case "IMAGERY" -> mapper.treeToValue(request.path("parameters"), ImageryProcessingParameters.class);
                        case "TERRAIN" -> mapper.treeToValue(request.path("parameters"), TerrainProcessingParameters.class);
                        case "" -> null;
                        default -> throw failure("INVALID_READ_OPTIONS", "栅格处理用途无效");
                    };
                    worker.statisticsOnly = "RASTER_STATISTICS".equals(request.path("operation").asText());
                    JsonNode range = request.path("automaticDisplayRange");
                    if (range.isArray() && range.size() == 2)
                        worker.automaticDisplayRange = List.of(range.get(0).asDouble(), range.get(1).asDouble());
                    worker.raster(input, request.path("sourceName").asString(), options, drivers, parameters);
                }
                worker.writeResult(worker.result);
                return;
            } catch (ReaderException ex) {
                response = error(mapper, ex.code(), ex.getMessage());
            } catch (LinkageError ex) {
                response = error(mapper, "GDAL_UNAVAILABLE", "GDAL Java/JNI 或原生依赖不可用: " + ex.getMessage());
            } catch (OutOfMemoryError ex) {
                response = error(mapper, "GDAL_OUTPUT_LIMIT", "GDAL 工作进程超过可用内存");
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                response = error(mapper, message.toLowerCase(Locale.ROOT).contains(".ige")
                        ? "MISSING_REQUIRED_MEMBERS" : "NATIVE_READ_FAILED", "GDAL Java 无法读取数据源: " + message);
            }
            mapper.writeValue(directory.resolve("result.json").toFile(), response);
            System.exit(1);
        }

        private static ObjectNode error(ObjectMapper mapper, String code, String message) {
            ObjectNode response = mapper.createObjectNode();
            ObjectNode error = response.putObject("error");
            error.put("code", code);
            error.put("message", message == null ? code : message.substring(0, Math.min(2000, message.length())));
            return response;
        }

        /** 所有初始化和进程级配置仅发生在隔离工作进程。 */
        private static void initialize(String expectedVersion) {
            if (expectedVersion == null || !expectedVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw failure("INVALID_GDAL_CONFIG", "GDAL 目标版本必须明确指定");
            String actual = gdal.VersionInfo("RELEASE_NAME");
            if (!expectedVersion.equals(actual)) throw failure("GDAL_UNAVAILABLE",
                    "GDAL Java 目标版本 " + expectedVersion + " 与原生版本 " + actual + " 不一致");
            gdal.SetConfigOption("PROJ_NETWORK", "OFF");
            gdal.SetConfigOption("GDAL_HTTP_TIMEOUT", "1");
            gdal.SetConfigOption("CPL_VSIL_CURL_ALLOWED_EXTENSIONS", ".ocean-disabled-network");
            gdal.SetConfigOption("GDAL_NUM_THREADS", "1");
            gdal.SetConfigOption("OGR_ARC_STEPSIZE", "1");
            gdal.SetConfigOption("GDAL_CACHEMAX", "64");
            gdal.UseExceptions(); org.gdal.ogr.ogr.UseExceptions(); org.gdal.osr.osr.UseExceptions();
            gdal.AllRegister();
        }

        private static List<String> drivers(GisFileFormat format) {
            List<String> available = new ArrayList<>();
            for (String name : format.driverNames().stream().sorted().toList()) {
                org.gdal.gdal.Driver driver = gdal.GetDriverByName(name);
                if (driver == null) continue;
                try {
                    if ("YES".equals(driver.GetMetadataItem("DCAP_OPEN"))
                            && "YES".equals(driver.GetMetadataItem(format.isVector() ? "DCAP_VECTOR" : "DCAP_RASTER"))) available.add(name);
                } finally { driver.delete(); }
            }
            return available;
        }

        private static void outputDriver(String name) {
            org.gdal.gdal.Driver driver = gdal.GetDriverByName(name);
            if (driver == null) throw failure("DRIVER_UNAVAILABLE", "GDAL 缺少目标格式驱动: " + name);
            try {
                if (!"YES".equals(driver.GetMetadataItem("DCAP_CREATE")) && !"YES".equals(driver.GetMetadataItem("DCAP_CREATECOPY")))
                    throw failure("DRIVER_UNAVAILABLE", "GDAL 目标格式没有写入能力: " + name);
            } finally { driver.delete(); }
        }

        private Dataset open(Path input, boolean vector, List<String> drivers, Vector<String> options) {
            gdal.ErrorReset();
            Dataset source = gdal.OpenEx(input.toString(), gdalconstConstants.OF_READONLY | gdalconstConstants.OF_VERBOSE_ERROR
                    | (vector ? gdalconstConstants.OF_VECTOR : gdalconstConstants.OF_RASTER), new Vector<>(drivers), options);
            if (source == null) throw failure("NATIVE_READ_FAILED", "GDAL 无法只读打开文件组入口");
            return source;
        }

        private void vectors(GisFileFormat format, Path input, JsonNode options, List<String> drivers) throws Exception {
            outputDriver("GeoJSON");
            String explicit = explicitCrs(options);
            if (format.sourceCrsRequirement() == GisFileFormat.Requirement.REQUIRED && explicit == null)
                throw failure("MISSING_CRS", "CAD 必须明确提供 sourceCrs");
            Vector<String> openOptions = new Vector<>();
            if (format == GisFileFormat.CSV && options.hasNonNull("xField")) openOptions.addAll(List.of("X_POSSIBLE_NAMES=" + fieldOption(options, "xField"),
                    "Y_POSSIBLE_NAMES=" + fieldOption(options, "yField"), "KEEP_GEOM_COLUMNS=YES", "AUTODETECT_TYPE=NO"));
            Dataset source = open(input, true, drivers, openOptions);
            try {
                Set<String> names = new HashSet<>();
                for (int index = 0; index < source.GetLayerCount(); index++) {
                    Layer layer = source.GetLayer(index);
                    if (layer == null) continue;
                    try {
                        String name = layer.GetName();
                        if (name == null || name.isBlank() || name.chars().anyMatch(c -> c < 32) || !names.add(name))
                            throw failure("INVALID_LAYER_NAME", "源图层名为空、重名或无法安全选择");
                        ObjectNode description = describeLayer(layer);
                        if (format == GisFileFormat.CSV && options.hasNonNull("xField")) validateCsvFields(description, options);
                        JsonNode geometryFields = description.path("geometryFields");
                        for (int geometryIndex = 0; geometryIndex < Math.max(1, geometryFields.size()); geometryIndex++) {
                            JsonNode geometry = geometryFields.isEmpty() ? json.createObjectNode() : geometryFields.get(geometryIndex);
                            String sourceCrs = explicit != null ? explicit : text(geometry.path("coordinateSystem").path("wkt"));
                            if (sourceCrs == null && !geometryFields.isEmpty()) throw failure("MISSING_CRS", "源图层 " + name + " 缺少源坐标系");
                            String parsedCrs = sourceCrs == null ? null : geotoolsCrs(sourceCrs);
                            String filename = "vector-" + result.path("vectors").size() + ".geojson";
                            Path output = directory.resolve(filename);
                            Vector<String> arguments = new Vector<>(List.of("-f", "GeoJSON", "-makevalid",
                                    "-nlt", "CONVERT_TO_LINEAR", "-lco", "RFC7946=YES", "-lco", "WRAPDATELINE=YES",
                                    "-lco", "COORDINATE_PRECISION=15", "-preserve_fid"));
                            if (sourceCrs != null) arguments.addAll(List.of("-t_srs", "EPSG:4326", "-s_srs", sourceCrs));
                            if (geometryFields.size() > 1) {
                                List<String> selectedFields = new ArrayList<>();
                                for (JsonNode field : description.path("fields"))
                                    selectedFields.add("\"" + field.path("name").asText().replace("\"", "\"\"") + "\"");
                                selectedFields.add("\"" + geometry.path("name").asText().replace("\"", "\"\"") + "\"");
                                arguments.addAll(List.of("-select", String.join(",", selectedFields)));
                            }
                            if (name.startsWith("-")) arguments.addAll(List.of("-sql", "SELECT * FROM \"" + name.replace("\"", "\"\"") + "\""));
                            else arguments.add(name);
                            progress.accept(ProgressStage.indeterminate("NATIVE_VECTOR_CONVERSION", "Java GDAL 转换源图层: " + name));
                            VectorTranslateOptions translate = new VectorTranslateOptions(arguments);
                            ProgressCallback callback = callback("NATIVE_VECTOR_CONVERSION", "转换源图层: " + name);
                            Dataset converted = null;
                            try {
                                converted = gdal.VectorTranslate(output.toString(), source, translate, callback);
                                if (converted == null) throw failure("EMPTY_NATIVE_OUTPUT", "GDAL 没有生成标准矢量数据");
                                if (converted.Close() != gdalconstConstants.CE_None) throw failure("NATIVE_READ_FAILED", "GDAL 矢量输出关闭失败");
                            } finally {
                                if (converted != null) converted.delete(); callback.delete(); translate.delete();
                            }
                            requireOutput(output);
                            ObjectNode metadata = json.createObjectNode();
                            org.gdal.gdal.Driver sourceDriver = source.GetDriver();
                            try { metadata.put("driver", sourceDriver.getShortName()); }
                            finally { sourceDriver.delete(); }
                            metadata.set("sourceLayer", description); metadata.set("readOptions", options); metadata.put("sourceCrs", sourceCrs);
                            metadata.putObject("geometryProcessing").put("makeValid", true).put("wrapDateline", true)
                                    .put("outputStandard", "RFC7946").put("rawSnapshotUnchanged", true);
                            String collectionKey = stableKey("layer", name);
                            metadata.put("sourceCollectionKey", collectionKey);
                            metadata.put("geometryField", geometry.path("name").asText(""));
                            ((ObjectNode) metadata.path("geometryProcessing")).put("curveStepDegrees", 1);
                            String key = geometryFields.size() > 1 ? stableKey("geometry", name + ":" + geometryIndex) : collectionKey;
                            append("vectors", key, name, filename, parsedCrs, geometry.get("extent"), metadata);
                        }
                    } finally { layer.delete(); }
                }
            } finally { source.delete(); }
            if (result.path("vectors").isEmpty()) throw failure("EMPTY_DATASET", "数据源不包含可读取集合");
        }

        /** 只查询驱动可直接提供的统计，未知值不强制遍历源要素。 */
        private ObjectNode describeLayer(Layer layer) {
            ObjectNode description = json.createObjectNode();
            description.put("name", layer.GetName());
            long count = -1;
            gdal.PushErrorHandler("CPLQuietErrorHandler");
            try { count = layer.GetFeatureCount(0); }
            catch (RuntimeException ignored) {
                // CAD 等驱动可能不支持无扫描计数；实际读取错误仍由后续转换报告。
            } finally { gdal.PopErrorHandler(); gdal.ErrorReset(); }
            description.put("featureCount", count);
            var definition = layer.GetLayerDefn();
            try {
                var fields = description.putArray("fields");
                for (int index = 0; index < definition.GetFieldCount(); index++) {
                    var field = definition.GetFieldDefn(index);
                    try {
                        ObjectNode item = fields.addObject();
                        item.put("name", field.GetName()); item.put("type", field.GetTypeName());
                    } finally { field.delete(); }
                }
                var geometryFields = description.putArray("geometryFields");
                for (int index = 0; index < definition.GetGeomFieldCount(); index++) {
                    var field = definition.GetGeomFieldDefn(index);
                    try {
                        ObjectNode geometry = geometryFields.addObject(); geometry.put("name", field.GetName());
                        // MapInfo 的真实空间参考可能只挂在层上，而未挂到几何字段定义。
                        String wkt = sourceWkt(field.GetSpatialRef());
                        if (wkt == null && index == 0) wkt = sourceWkt(layer.GetSpatialRef());
                        if (wkt != null) geometry.putObject("coordinateSystem").put("wkt", wkt);
                        if (index == 0) {
                            double[] bounds = new double[4];
                            gdal.PushErrorHandler("CPLQuietErrorHandler");
                            try {
                                if (layer.GetExtent(bounds, 0) == gdalconstConstants.CE_None) {
                                    var extent = geometry.putArray("extent");
                                    extent.add(bounds[0]).add(bounds[2]).add(bounds[1]).add(bounds[3]);
                                }
                            } catch (RuntimeException ignored) {
                                // 不支持无扫描范围查询时保留未知，不借用转换后的经纬度范围。
                            } finally { gdal.PopErrorHandler(); gdal.ErrorReset(); }
                        }
                    } finally { field.delete(); }
                }
            } finally { definition.delete(); }
            return description;
        }

        /** CAD 可返回非空包装对象但没有实际 SRS；最终仍须从源声明或显式配置取得有效坐标系。 */
        private static String sourceWkt(SpatialReference reference) {
            if (reference == null) return null;
            gdal.PushErrorHandler("CPLQuietErrorHandler");
            try {
                String[] output = new String[1];
                if (reference.ExportToWkt(output) != 0 || output[0] == null || output[0].isBlank()) return null;
                return output[0];
            } catch (RuntimeException ex) {
                return null;
            } finally {
                reference.delete();
                gdal.PopErrorHandler();
                gdal.ErrorReset();
            }
        }

        /** 一份容器可产生多个栅格集合；集合键与原始子数据集编号绑定。 */
        private void raster(Path input, String sourceName, JsonNode options, List<String> drivers,
                            GisProcessingParameters parameters) throws Exception {
            outputDriver("GTiff");
            Dataset source = open(input, false, drivers, new Vector<>());
            try {
                JsonNode info = rasterInfo(source);
                boolean found = false;
                JsonNode subdatasets = info.path("metadata").path("SUBDATASETS");
                boolean hasSubdatasets = subdatasets.propertyNames().stream().anyMatch(field -> field.matches("SUBDATASET_[1-9][0-9]*_NAME"));
                if (parameters != null && (parameters.raster() == null || parameters.raster().layerKeys() == null)
                        && subdatasets.propertyNames().stream().filter(field -> field.matches("SUBDATASET_[1-9][0-9]*_NAME")).count() > 1)
                    throw failure("INPUT_OPTIONS_REQUIRED", "数据包含多个栅格集合，请选择需要切片的集合");
                if (source.GetRasterCount() > 0 && !hasSubdatasets) {
                    rasterDataset(source, info, "default", sourceName, options, parameters);
                    found = true;
                }
                for (String field : subdatasets.propertyNames()) {
                    if (!field.matches("SUBDATASET_[1-9][0-9]*_NAME")) continue;
                    String name = subdatasets.path(field).asText();
                    // 只能打开当前受控入口内部的子数据集，禁止外部数据源和虚拟网络路径。
                    if (!name.contains(input.toString()) || name.contains("/vsi") || name.contains("://"))
                        throw failure("INVALID_MEMBER_PATH", "子数据集引用超出受控来源");
                    Dataset subset = gdal.OpenEx(name, gdalconstConstants.OF_READONLY | gdalconstConstants.OF_RASTER,
                            new Vector<>(drivers), new Vector<>());
                    if (subset == null) throw failure("NATIVE_READ_FAILED", "子数据集无法读取: " + field);
                    try {
                        String key = "subdataset:" + field.substring(11, field.length() - 5);
                        rasterDataset(subset, rasterInfo(subset), key, sourceName + " / " + key, options, parameters);
                        found = true;
                    } finally { subset.delete(); }
                }
                if (!found) throw failure("EMPTY_RASTER", "数据源没有栅格集合");
                if (selectedRasterCount == 0) throw failure("INPUT_OPTIONS_REQUIRED", "选定的栅格集合不存在");
            } finally { source.delete(); }
        }

        private JsonNode rasterInfo(Dataset source) {
            String openingWarning = gdal.GetLastErrorType() == gdalconstConstants.CE_Warning ? gdal.GetLastErrorMsg() : null;
            InfoOptions options = new InfoOptions(new Vector<>(List.of("-json", "-mdd", "all")));
            try {
                ObjectNode info = (ObjectNode) json.readTree(gdal.GDALInfo(source, options));
                if (openingWarning != null && !openingWarning.isBlank())
                    info.putArray("nativeWarnings").add(openingWarning.substring(0, Math.min(2000, openingWarning.length())));
                return info;
            }
            finally { options.delete(); }
        }

        /** 来源数值保留在原始文件；标准化过程和业务派生规则随元数据记录。 */
        private void rasterDataset(Dataset source, JsonNode info, String key, String sourceName, JsonNode options,
                                   GisProcessingParameters parameters) throws Exception {
            if (parameters != null && parameters.raster() != null && parameters.raster().layerKeys() != null
                    && !parameters.raster().layerKeys().contains(key)) return;
            selectedRasterCount++;
            String explicit = explicitCrs(options);
            String sourceCrs = explicit != null ? explicit : text(info.path("coordinateSystem").path("wkt"));
            boolean affine = info.path("geoTransform").isArray() && info.path("geoTransform").size() == 6;
            boolean gcps = info.path("gcps").path("gcpList").isArray() && !info.path("gcps").path("gcpList").isEmpty();
            boolean rpc = !info.path("metadata").path("RPC").isEmpty();
            if (sourceCrs == null && gcps) sourceCrs = text(info.path("gcps").path("coordinateSystem").path("wkt"));
            if (sourceCrs == null && rpc) sourceCrs = "EPSG:4326";
            if (sourceCrs == null) throw failure("MISSING_CRS", "栅格缺少源坐标系，请补充 sourceCrs");
            if (!affine && !gcps && !rpc) throw failure("MISSING_GEOLOCATION", "栅格缺少仿射、GCP或RPC定位信息");
            if (affine) {
                JsonNode transform = info.path("geoTransform");
                for (JsonNode coefficient : transform)
                    if (!coefficient.isNumber() || !Double.isFinite(coefficient.asDouble()))
                        throw failure("INVALID_GEOLOCATION", "栅格定位包含无效数值");
                double determinant = transform.get(1).asDouble() * transform.get(5).asDouble()
                        - transform.get(2).asDouble() * transform.get(4).asDouble();
                if (!Double.isFinite(determinant) || determinant == 0)
                    throw failure("INVALID_GEOLOCATION", "栅格仿射定位不可逆");
            }
            if (source.GetRasterCount() == 0) throw failure("EMPTY_RASTER", "集合没有可读取波段");
            var warnings = json.createArrayNode();
            if (info.path("nativeWarnings").isArray()) warnings.addAll((tools.jackson.databind.node.ArrayNode) info.path("nativeWarnings"));
            for (int index = 1; index <= source.GetRasterCount(); index++) {
                progress.accept(ProgressStage.determinate("NATIVE_INSPECTION", "校验栅格波段网格", index - 1,
                        source.GetRasterCount(), ProgressUnit.OPERATION));
                var band = source.GetRasterBand(index);
                try {
                    if (band.GetXSize() != source.GetRasterXSize() || band.GetYSize() != source.GetRasterYSize())
                        throw failure("INCONSISTENT_RASTER_GRID", "波段 " + index + " 与数据集网格尺寸不一致");
                    // 容器驱动可能统一报告尺寸，真实网格冲突直到首次解码才通过原生诊断暴露。
                    gdal.ErrorReset();
                    int status = band.ReadRaster(0, 0, 1, 1, new double[1]);
                    String detail = gdal.GetLastErrorMsg();
                    if (status != gdalconstConstants.CE_None || gdal.GetLastErrorType() >= gdalconstConstants.CE_Failure)
                        throw failure("NATIVE_READ_FAILED", "波段 " + index + " 无法读取: " + detail);
                    if (gdal.GetLastErrorType() == gdalconstConstants.CE_Warning && detail != null && !detail.isBlank()) {
                        if (detail.contains("Georeferencing of band") && detail.contains("data access may be incomplete"))
                            throw failure("INCONSISTENT_RASTER_GRID", "波段 " + index + " 的实际网格与容器不一致，定位或读取可能不完整: " + detail);
                        if (warnings.size() < 64) warnings.add(detail.substring(0, Math.min(2000, detail.length())));
                    }
                } finally { band.delete(); gdal.ErrorReset(); }
            }
            RasterPlan plan = parameters == null ? null : planRaster(info, parameters);
            if (statisticsOnly) {
                if (plan != null && plan.needsDisplayRange(info)) collectDisplayRange(source, plan);
                return;
            }
            boolean automaticRange = plan != null && plan.needsDisplayRange(info);
            if (automaticRange) {
                if (automaticDisplayRange == null)
                    throw failure("INPUT_OPTIONS_REQUIRED", "影像缺少自动显示范围统计，请重新提交切片任务");
                plan = new RasterPlan(plan.purpose(), plan.targetCrs(), plan.bands(), automaticDisplayRange,
                        plan.elevationScale(), plan.elevationOffset(), plan.resampling(), plan.palette());
            }
            String filename = "raster-" + result.path("rasters").size() + ".tif";
            Path output = directory.resolve(filename);
            Vector<String> select = new Vector<>(List.of("-of", "VRT"));
            if (explicit != null) select.addAll(List.of("-a_srs", sourceCrs));
            if (plan != null) {
                // 派生数值可合法等于源NoData；有效性另行保存，避免继承的掩膜阻止创建统一掩膜。
                select.addAll(List.of("-a_nodata", "none", "-mask", "none"));
                for (int band : plan.bands()) select.addAll(List.of("-b", Integer.toString(band)));
                if (plan.palette()) select.addAll(List.of("-expand", "rgba"));
                if ("IMAGERY".equals(plan.purpose())) {
                    select.addAll(List.of("-ot", "Byte"));
                    if (!plan.palette()) {
                        for (int index = 1; index <= plan.bands().size(); index++) {
                            boolean alpha = index == plan.bands().size() && (index == 2 || index == 4);
                            if (alpha) {
                                // 透明度按GDAL的满量程约定转换，不能套用颜色亮度范围。
                                JsonNode band = info.path("bands").get(plan.bands().get(index - 1) - 1);
                                int bits = band.path("metadata").path("IMAGE_STRUCTURE").path("NBITS").asInt(0);
                                double maximum = bits > 0 && bits <= 64 ? Math.scalb(1d, bits) - 1
                                        : switch (band.path("type").asText()) {
                                            case "UInt16" -> 65535;
                                            case "Int16" -> 32767;
                                            default -> 255;
                                        };
                                select.addAll(List.of("-scale_" + index, "0", Double.toString(maximum), "0", "255"));
                            } else if (plan.displayRange() != null && (!automaticRange
                                    || !"Byte".equals(info.path("bands").get(plan.bands().get(index - 1) - 1).path("type").asText()))) {
                                select.addAll(List.of("-scale_" + index, plan.displayRange().get(0).toString(),
                                        plan.displayRange().get(1).toString(), "0", "255", "-exponent_" + index, "1"));
                            }
                        }
                    }
                    if (!plan.palette()) select.addAll(List.of("-colorinterp", switch (plan.bands().size()) {
                        case 1 -> "gray";
                        case 2 -> "gray,alpha";
                        case 3 -> "red,green,blue";
                        default -> "red,green,blue,alpha";
                    }));
                } else {
                    select.addAll(List.of("-ot", "Float32", "-scale", "0", "1", Double.toString(plan.elevationOffset()),
                            Double.toString(plan.elevationOffset() + plan.elevationScale())));
                }
            }
            TranslateOptions selection = new TranslateOptions(select);
            Dataset selected = null;
            try {
                selected = gdal.Translate(directory.resolve(filename + ".vrt").toString(), source, selection);
                if (selected == null) throw failure("EMPTY_NATIVE_OUTPUT", "无法准备标准栅格波段");
                if (plan != null) {
                    Path validityPath = directory.resolve(filename + ".validity.tif");
                    writeFootprintProxy(source, validityPath, plan.bands());
                    if (selected.CreateMaskBand(gdalconstConstants.GMF_PER_DATASET) != gdalconstConstants.CE_None)
                        throw failure("NATIVE_READ_FAILED", "无法创建处理输入有效性掩膜");
                    var firstBand = selected.GetRasterBand(1);
                    var mask = firstBand.GetMaskBand();
                    try {
                        // VRT只描述来源，固定文件名不包含外部输入；不向虚拟波段写入像元。
                        String maskSource = "<SimpleSource><SourceFilename relativeToVRT=\"1\">"
                                + filename + ".validity.tif</SourceFilename><SourceBand>1</SourceBand></SimpleSource>";
                        if (mask.SetMetadataItem("source_0", maskSource, "new_vrt_sources") != gdalconstConstants.CE_None)
                            throw failure("NATIVE_READ_FAILED", "无法关联处理输入有效性掩膜");
                    } finally { mask.delete(); firstBand.delete(); }
                }
                Vector<String> arguments = new Vector<>(List.of("-of", "GTiff", "-co", "TILED=YES",
                        "-co", "BLOCKXSIZE=256", "-co", "BLOCKYSIZE=256", "-co", "BIGTIFF=IF_SAFER"));
                boolean warp = plan != null || !affine;
                String target = plan == null ? "EPSG:4326" : plan.targetCrs();
                String algorithm = plan == null ? "near" : plan.resampling();
                ProgressCallback callback = callback("NATIVE_RASTER_CONVERSION", "标准化栅格集合: " + sourceName);
                Dataset converted = null;
                try {
                    if (warp) {
                        arguments.addAll(List.of("-t_srs", target, "-r", algorithm, "-et", "0", "-wm", "64"));
                        if (!affine && rpc) arguments.add("-rpc");
                        else if (!affine && gcps) arguments.add("-tps");
                        else arguments.addAll(List.of("-s_srs", sourceCrs));
                        if (affine && plan != null) {
                            SpatialReference reference = new SpatialReference();
                            try {
                                reference.SetFromUserInput(sourceCrs);
                                if (reference.IsGeographic() == 1) {
                                    double[] transform = source.GetGeoTransform();
                                    double west = transform[0];
                                    double east = west + source.GetRasterXSize() * transform[1];
                                    if (transform[2] == 0 && transform[4] == 0 && west >= 0 && east > 180) {
                                        double south = transform[3] + source.GetRasterYSize() * transform[5];
                                        double north = transform[3];
                                        double latitude = "EPSG:3857".equals(target) ? 85.0511287798066 : 90;
                                        arguments.addAll(List.of("-te_srs", sourceCrs, "-te", "-180",
                                                Double.toString(Math.max(-latitude, south)), "180",
                                                Double.toString(Math.min(latitude, north))));
                                    }
                                }
                            } finally { reference.delete(); }
                        }
                        if (plan != null && "IMAGERY".equals(plan.purpose())) arguments.add("-dstalpha");
                        if (plan != null && "TERRAIN".equals(plan.purpose())) arguments.addAll(List.of("-dstnodata", "nan"));
                        WarpOptions warping = new WarpOptions(arguments);
                        try { converted = gdal.Warp(output.toString(), new Dataset[]{selected}, warping, callback); }
                        finally { warping.delete(); }
                    } else {
                        arguments.addAll(List.of("-co", "COPY_SRC_MDD=YES", "-mask", "auto"));
                        TranslateOptions translation = new TranslateOptions(arguments);
                        try { converted = gdal.Translate(output.toString(), selected, translation, callback); }
                        finally { translation.delete(); }
                    }
                    if (converted == null) throw failure("EMPTY_NATIVE_OUTPUT", "GDAL 没有生成标准栅格");
                    if (plan != null) {
                        for (int index = 1; index <= converted.GetRasterCount(); index++) {
                            var band = converted.GetRasterBand(index);
                            try {
                                band.SetScale(1); band.SetOffset(0);
                                band.SetUnitType("TERRAIN".equals(plan.purpose()) ? "m" : "");
                            } finally { band.delete(); }
                        }
                    }
                    ObjectNode metadata = json.createObjectNode();
                    metadata.set("sourceDataset", info); metadata.set("readOptions", options);
                    if (!warnings.isEmpty()) metadata.set("nativeWarnings", warnings);
                    metadata.set("normalizedDataset", rasterInfo(converted));
                    if (plan == null && metadata.path("normalizedDataset").path("bands").valueStream().anyMatch(band ->
                            !Set.of("Byte", "UInt16", "Int16", "UInt32", "Int32", "Float32", "Float64")
                                    .contains(band.path("type").asText()))) {
                        String footprintFile = filename + ".footprint.tif";
                        writeFootprintProxy(converted, directory.resolve(footprintFile), null);
                        metadata.put("footprintFile", footprintFile);
                    }
                    ObjectNode normalization = metadata.putObject("normalization");
                    normalization.put("resampled", warp).put("resampling", warp ? algorithm : "NONE")
                            .put("coordinateErrorThresholdPixels", 0).put("rawSourceUnchanged", true);
                    if (plan != null) metadata.set("processingPlan", json.valueToTree(plan));
                    if (converted.Close() != gdalconstConstants.CE_None) throw failure("NATIVE_READ_FAILED", "标准栅格关闭失败");
                    requireOutput(output);
                    append("rasters", key, sourceName, filename, geotoolsCrs(sourceCrs), info.get("cornerCoordinates"), metadata);
                } finally {
                    if (converted != null) converted.delete();
                    callback.delete();
                }
            } finally {
                if (selected != null) selected.delete();
                selection.delete();
            }
        }

        /** 分块扫描有效颜色值；忽略NoData、非有限值、掩膜外和完全透明的像元，不写回来源统计。 */
        private void collectDisplayRange(Dataset source, RasterPlan plan) {
            int width = source.GetRasterXSize(), height = source.GetRasterYSize();
            int colors = plan.bands().size() == 2 || plan.bands().size() == 4
                    ? plan.bands().size() - 1 : plan.bands().size();
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            for (int y = 0; y < height; y += 256) {
                for (int x = 0; x < width; x += 256) {
                    int columns = Math.min(256, width - x), rows = Math.min(256, height - y);
                    boolean[] transparent = new boolean[columns * rows];
                    for (int index = 1; index <= source.GetRasterCount(); index++) {
                        var band = source.GetRasterBand(index);
                        try {
                            boolean selectedAlpha = colors < plan.bands().size() && index == plan.bands().getLast();
                            if (!selectedAlpha && band.GetColorInterpretation() != gdalconstConstants.GCI_AlphaBand) continue;
                            double[] alpha = new double[transparent.length];
                            if (band.ReadRaster(x, y, columns, rows, alpha) != gdalconstConstants.CE_None)
                                throw failure("NATIVE_READ_FAILED", "统计显示范围时读取透明度失败");
                            for (int pixel = 0; pixel < alpha.length; pixel++)
                                if (!Double.isFinite(alpha[pixel]) || alpha[pixel] <= 0) transparent[pixel] = true;
                        } finally { band.delete(); }
                    }
                    for (int index = 0; index < colors; index++) {
                        var band = source.GetRasterBand(plan.bands().get(index));
                        var mask = band.GetMaskBand();
                        try {
                            if (band.GetRasterDataType() == gdalconstConstants.GDT_Byte) continue;
                            double[] values = new double[transparent.length];
                            byte[] validity = new byte[transparent.length];
                            Double[] noData = new Double[1];
                            band.GetNoDataValue(noData);
                            if (band.ReadRaster(x, y, columns, rows, values) != gdalconstConstants.CE_None
                                    || mask.ReadRaster(x, y, columns, rows, validity) != gdalconstConstants.CE_None)
                                throw failure("NATIVE_READ_FAILED", "自动显示范围读取失败");
                            for (int pixel = 0; pixel < values.length; pixel++) {
                                double value = values[pixel];
                                if (transparent[pixel] || validity[pixel] == 0 || !Double.isFinite(value)
                                        || noData[0] != null && value == noData[0]) continue;
                                minimum = Math.min(minimum, value);
                                maximum = Math.max(maximum, value);
                            }
                        } finally { mask.delete(); band.delete(); }
                    }
                }
                progress.accept(ProgressStage.determinate("RASTER_STATISTICS", "自动统计影像显示范围",
                        Math.min(height, y + 256), height, ProgressUnit.OPERATION));
            }
            if (!Double.isFinite(minimum)) throw failure("EMPTY_RASTER", "所选影像波段没有可用于切片的有效像元");
            JsonNode previous = result.path("displayRange");
            if (previous.isArray()) {
                minimum = Math.min(minimum, previous.get(0).asDouble());
                maximum = Math.max(maximum, previous.get(1).asDouble());
            }
            result.set("displayRange", json.valueToTree(List.of(minimum, maximum)));
        }

        /** 原生按所选波段固定有效性，供轮廓提取和处理转换复用，原始数值不降精度。 */
        private void writeFootprintProxy(Dataset source, Path path, List<Integer> selectedBands) {
            int width = source.GetRasterXSize(), height = source.GetRasterYSize();
            var driver = gdal.GetDriverByName("GTiff");
            Dataset proxy;
            try {
                proxy = driver.Create(path.toString(), width, height, 1, gdalconstConstants.GDT_Byte,
                        new String[]{"TILED=YES", "BLOCKXSIZE=256", "BLOCKYSIZE=256", "BIGTIFF=IF_SAFER"});
            } finally { driver.delete(); }
            if (proxy == null) throw failure("EMPTY_NATIVE_OUTPUT", "无法创建有效性代理");
            try {
                proxy.SetProjection(source.GetProjection()); proxy.SetGeoTransform(source.GetGeoTransform());
                var output = proxy.GetRasterBand(1);
                try {
                    output.SetNoDataValue(0);
                    for (int y = 0; y < height; y += 256) {
                        for (int x = 0; x < width; x += 256) {
                            int columns = Math.min(256, width - x), rows = Math.min(256, height - y);
                            byte[] valid = new byte[columns * rows];
                            for (int index = 1; index <= source.GetRasterCount(); index++) {
                                if (selectedBands != null && !selectedBands.contains(index)) continue;
                                var band = source.GetRasterBand(index);
                                var mask = band.GetMaskBand();
                                try {
                                    if (band.GetColorInterpretation() == gdalconstConstants.GCI_AlphaBand) continue;
                                    byte[] values = new byte[valid.length];
                                    if (mask.ReadRaster(x, y, columns, rows, values) != gdalconstConstants.CE_None)
                                        throw failure("NATIVE_READ_FAILED", "有效性掩膜读取失败");
                                    String type = gdal.GetDataTypeName(band.GetRasterDataType());
                                    boolean complex = type.startsWith("C");
                                    double[] numbers = type.contains("Float") ? new double[valid.length * (complex ? 2 : 1)] : null;
                                    if (numbers != null && band.ReadRaster(x, y, columns, rows, columns, rows,
                                            complex ? gdalconstConstants.GDT_CFloat64 : gdalconstConstants.GDT_Float64, numbers) != gdalconstConstants.CE_None)
                                        throw failure("NATIVE_READ_FAILED", "特殊波段数值读取失败");
                                    for (int pixel = 0; pixel < valid.length; pixel++) {
                                        if (values[pixel] == 0) continue;
                                        int component = pixel * (complex ? 2 : 1);
                                        if (numbers == null || Double.isFinite(numbers[component])
                                                && (!complex || Double.isFinite(numbers[component + 1]))) valid[pixel] = 1;
                                    }
                                } finally { mask.delete(); band.delete(); }
                            }
                            for (int index = 1; index <= source.GetRasterCount(); index++) {
                                var band = source.GetRasterBand(index);
                                try {
                                    if (band.GetColorInterpretation() != gdalconstConstants.GCI_AlphaBand) continue;
                                    double[] alpha = new double[valid.length];
                                    if (band.ReadRaster(x, y, columns, rows, alpha) != gdalconstConstants.CE_None)
                                        throw failure("NATIVE_READ_FAILED", "透明度读取失败");
                                    for (int pixel = 0; pixel < valid.length; pixel++)
                                        if (!Double.isFinite(alpha[pixel]) || alpha[pixel] <= 0) valid[pixel] = 0;
                                } finally { band.delete(); }
                            }
                            if (output.WriteRaster(x, y, columns, rows, valid) != gdalconstConstants.CE_None)
                                throw failure("NATIVE_READ_FAILED", "有效性代理写入失败");
                        }
                        progress.accept(ProgressStage.determinate("RASTER_VALIDITY", "扫描特殊波段有效像元",
                                Math.min(height, y + 256), height, ProgressUnit.OPERATION));
                    }
                } finally { output.delete(); }
                if (proxy.Close() != gdalconstConstants.CE_None) throw failure("NATIVE_READ_FAILED", "有效性代理关闭失败");
            } finally { proxy.delete(); }
        }

        private ProgressCallback callback(String code, String message) {
            return new ProgressCallback() {
                @Override public int run(double completed, String detail) {
                    if (Double.isFinite(completed)) progress.accept(ProgressStage.determinate(code, message,
                            Math.max(0, Math.min(1000, Math.round(completed * 1000))), 1000, ProgressUnit.OPERATION));
                    return 1;
                }
            };
        }

        private static String geotoolsCrs(String sourceCrs) {
            if (sourceCrs.matches("EPSG:[0-9]{1,9}")) return sourceCrs;
            SpatialReference reference = new SpatialReference();
            try {
                if (reference.SetFromUserInput(sourceCrs) != 0) throw failure("UNSUPPORTED_CRS", "源坐标系无法解析");
                reference.SetAxisMappingStrategy(org.gdal.osr.osrConstants.OAMS_TRADITIONAL_GIS_ORDER);
                String[] output = new String[1];
                if (reference.ExportToWkt(output, new Vector<>(List.of("FORMAT=WKT1_GDAL"))) != 0 || output[0] == null
                        || !output[0].matches("(?s)(GEOGCS|PROJCS|GEOCCS|COMPD_CS|VERT_CS)\\[.*"))
                    throw failure("UNSUPPORTED_CRS", "源坐标系不能转换为当前解析器支持的 WKT1");
                return output[0];
            } finally { reference.delete(); }
        }

        private void append(String kind, String key, String name, String path, String sourceCrs, JsonNode extent, JsonNode metadata) {
            ObjectNode item = ((tools.jackson.databind.node.ArrayNode) result.path(kind)).addObject();
            item.put("key", key); item.put("name", name); item.put("path", path); item.put("sourceCrs", sourceCrs);
            if (extent != null) item.set("sourceExtent", extent);
            item.set("metadata", metadata);
            if (json.writeValueAsBytes(result).length > metadataLimit) throw failure("GDAL_OUTPUT_LIMIT", "GDAL 元数据超过读取限额");
        }

        private void writeResult(ObjectNode value) throws IOException {
            byte[] bytes = json.writeValueAsBytes(value);
            if (bytes.length > metadataLimit) throw failure("GDAL_OUTPUT_LIMIT", "GDAL 元数据超过读取限额");
            Files.write(directory.resolve("result.json"), bytes);
        }
    }

    public static final class ReaderException extends IllegalArgumentException {
        private final String code;
        private ReaderException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
    private static ReaderException failure(String code, String message) { return new ReaderException(code, message); }
}
