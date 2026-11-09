package org.ocean.admin.gis.service;

import org.gdal.gdal.Dataset;
import org.gdal.gdal.InfoOptions;
import org.gdal.gdal.ProgressCallback;
import org.gdal.gdal.TranslateOptions;
import org.gdal.gdal.VectorTranslateOptions;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconstConstants;
import org.gdal.ogr.Layer;
import org.gdal.osr.SpatialReference;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private final ControlledWorkspaceResolver sources;
    private final ObjectMapper json;

    public GdalDatasetReader(GisProcessingProperties properties, ControlledWorkspaceResolver sources, ObjectMapper json) {
        this.properties = properties;
        this.sources = sources;
        this.json = json;
    }

    /** 原文件始终只读；临时文件随上下文关闭删除，提交前须再次核验所有源。 */
    public ReadContext prepare(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                               Consumer<ProgressStage> progress) {
        return prepare(group, members, heartbeat, progress, false);
    }

    /** 原始快照与制图转换独立执行，空层或无效制图数据也可留存供检查。 */
    public ReadContext prepareSnapshot(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                                       Consumer<ProgressStage> progress) {
        return prepare(group, members, heartbeat, progress, true);
    }

    private ReadContext prepare(GisFileGroup group, List<GisFileMeta> members, Runnable heartbeat,
                                Consumer<ProgressStage> progress, boolean snapshotOnly) {
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
            if (!snapshotOnly && format == GisFileFormat.GEOJSON && members.size() == 1) {
                entryPath = context.snapshots.getFirst().path();
            } else {
                Path staged = context.directory().resolve("source");
                Files.createDirectory(staged);
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
                        }
                    }
                }
                entryPath = staged.resolve(memberPath(entry.getMemberPath()));
                for (GisFileMeta member : members) {
                    if (GisFileFormat.extension(member.getMemberPath()).equals("tab")) {
                        validateTabReferences(staged.resolve(memberPath(member.getMemberPath())), staged, paths);
                    }
                }
            }
            context.verifySources();
            if (snapshotOnly) {
                ObjectNode request = json.createObjectNode().put("packageOperation", "SNAPSHOT")
                        .put("input", entryPath.toString()).put("format", format.name());
                request.set("options", options);
                context.packageResult = executePackage(context, request, progress);
            } else if (format == GisFileFormat.GEOJSON) {
                context.vectors.add(new VectorSource("default", entry.getOriginalName(), entryPath, "EPSG:4326", null, null));
            } else if (format == GisFileFormat.GEOTIFF && !options.has("sourceCrs")) {
                context.rasters.add(new RasterSource("default", entry.getOriginalName(), entryPath, null, null, null));
            } else {
                progress.accept(ProgressStage.indeterminate("NATIVE_INSPECTION", "检查原始驱动、坐标系和数据结构"));
                prepareWithWorker(context, format, entryPath, entry.getOriginalName(), options, progress);
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
        try (var context = new ReadContext(heartbeat)) {
            return executePackage(context, request, stage -> { });
        } catch (IOException ex) { throw failure("PACKAGE_IO", "矢量包操作失败: " + ex.getMessage()); }
    }

    private JsonNode executePackage(ReadContext context, ObjectNode request, Consumer<ProgressStage> progress) throws IOException {
        try {
            request.put("version", properties.getGdalVersion());
            request.put("maxMetadataBytes", Math.max(properties.getGdalMaxMetadataBytes(), 16 * 1024 * 1024));
            if (Set.of("QUERY", "TILE", "WINDOW").contains(
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
                                   JsonNode options, Consumer<ProgressStage> progress) throws Exception {
        Path directory = context.directory();
        ObjectNode request = json.createObjectNode();
        request.put("format", format.name());
        request.put("input", input.toString());
        request.put("sourceName", sourceName);
        request.set("options", options);
        request.put("version", properties.getGdalVersion());
        request.put("maxMetadataBytes", properties.getGdalMaxMetadataBytes());
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
        for (JsonNode source : result.path("vectors")) {
            context.vectors.add(new VectorSource(source.path("key").asString(), source.path("name").asString(),
                    resultPath(directory, source), source.path("sourceCrs").asString(), source.get("sourceExtent"), source.get("metadata")));
        }
        for (JsonNode source : result.path("rasters")) {
            context.rasters.add(new RasterSource(source.path("key").asString(), source.path("name").asString(),
                    resultPath(directory, source), source.path("sourceCrs").asString(), source.get("sourceExtent"), source.get("metadata")));
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
    public record RasterSource(String key, String name, Path geoTiffPath, String sourceCrs,
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

        private ReadContext(Runnable heartbeat) { this.heartbeat = heartbeat; }
        public List<VectorSource> vectors() { return List.copyOf(vectors); }
        public List<RasterSource> rasters() { return List.copyOf(rasters); }
        public JsonNode packageResult() { return packageResult; }
        public Path snapshotPath() throws IOException { return directory().resolve("snapshot.gpkg"); }

        private Path directory() throws IOException {
            if (directory == null) {
                Path root = Path.of(properties.getBasePath()).toAbsolutePath().normalize().resolve("native-read");
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
            ObjectMapper mapper = new ObjectMapper();
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
                    outputDriver("GeoJSON"); outputDriver("GTiff");
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
                if (request.has("packageOperation")) {
                    worker.writeResult(GeoPackageStore.executeNative(request, directory, mapper));
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
                else worker.raster(input, request.path("sourceName").asString(), options, drivers);
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
            Dataset source = gdal.OpenEx(input.toString(), gdalconstConstants.OF_READONLY | gdalconstConstants.OF_VERBOSE_ERROR
                    | (vector ? gdalconstConstants.OF_VECTOR : gdalconstConstants.OF_RASTER), new Vector<>(drivers), options);
            if (source == null) throw failure("NATIVE_READ_FAILED", "GDAL 无法只读打开文件组入口");
            return source;
        }

        private void vectors(GisFileFormat format, Path input, JsonNode options, List<String> drivers) throws Exception {
            outputDriver("GeoJSON");
            String explicit = explicitCrs(options);
            if ((format == GisFileFormat.DXF || format == GisFileFormat.DWG || format == GisFileFormat.CSV) && explicit == null)
                throw failure("MISSING_CRS", "CSV 和 CAD 必须明确提供 sourceCrs");
            Vector<String> openOptions = new Vector<>();
            if (format == GisFileFormat.CSV) openOptions.addAll(List.of("X_POSSIBLE_NAMES=" + fieldOption(options, "xField"),
                    "Y_POSSIBLE_NAMES=" + fieldOption(options, "yField"), "KEEP_GEOM_COLUMNS=YES", "AUTODETECT_TYPE=NO"));
            Dataset source = open(input, true, drivers, openOptions);
            try {
                Set<String> names = new HashSet<>();
                for (int index = 0; index < source.GetLayerCount(); index++) {
                    Layer layer = source.GetLayer(index);
                    if (layer == null) continue;
                    try {
                        String name = layer.GetName();
                        if (name == null || name.isBlank() || name.startsWith("-") || name.chars().anyMatch(c -> c < 32) || !names.add(name))
                            throw failure("INVALID_LAYER_NAME", "源图层名为空、重名或无法安全选择");
                        ObjectNode description = describeLayer(layer);
                        if (format == GisFileFormat.CSV) validateCsvFields(description, options);
                        JsonNode geometryFields = description.path("geometryFields");
                        if (geometryFields.isEmpty() || description.path("featureCount").asLong(-1) == 0) continue;
                        if (geometryFields.size() != 1) throw failure("MULTIPLE_GEOMETRY_FIELDS", "源图层包含多个几何字段，须明确选择后读取");
                        JsonNode geometry = geometryFields.get(0);
                        String sourceCrs = explicit != null ? explicit : text(geometry.path("coordinateSystem").path("wkt"));
                        if (sourceCrs == null) throw failure("MISSING_CRS", "源图层 " + name + " 缺少源坐标系");
                        String parsedCrs = geotoolsCrs(sourceCrs);
                        String filename = "vector-" + result.path("vectors").size() + ".geojson";
                        Path output = directory.resolve(filename);
                        Vector<String> arguments = new Vector<>(List.of("-f", "GeoJSON", "-t_srs", "EPSG:4326",
                                "-s_srs", sourceCrs, "-lco", "RFC7946=YES", "-lco", "COORDINATE_PRECISION=15", "-preserve_fid", name));
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
                        if (!hasFeatures(output)) {
                            Files.delete(output);
                            continue;
                        }
                        ObjectNode metadata = json.createObjectNode();
                        org.gdal.gdal.Driver sourceDriver = source.GetDriver();
                        try { metadata.put("driver", sourceDriver.getShortName()); }
                        finally { sourceDriver.delete(); }
                        metadata.set("sourceLayer", description); metadata.set("readOptions", options); metadata.put("sourceCrs", sourceCrs);
                        append("vectors", stableKey("layer", name), name, filename, parsedCrs, geometry.get("extent"), metadata);
                    } finally { layer.delete(); }
                }
            } finally { source.delete(); }
            if (result.path("vectors").isEmpty()) throw failure("EMPTY_GEOMETRY", "数据源不包含非空空间图层");
        }

        /** 未知计数的 GPX 等空层不应使有效图层失败；只流式检查临时输出数组的首个元素。 */
        private boolean hasFeatures(Path output) throws IOException {
            try (var parser = json.createParser(output.toFile())) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw failure("INVALID_NATIVE_METADATA", "标准矢量输出不是对象");
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw failure("INVALID_NATIVE_METADATA", "标准矢量输出结构无效");
                    String name = parser.currentName();
                    JsonToken token = parser.nextToken();
                    if (name.equals("features")) {
                        if (token != JsonToken.START_ARRAY) throw failure("INVALID_NATIVE_METADATA", "标准矢量输出缺少要素数组");
                        JsonToken first = parser.nextToken();
                        if (first == JsonToken.END_ARRAY) return false;
                        if (first == JsonToken.START_OBJECT) return true;
                        throw failure("INVALID_NATIVE_METADATA", "标准矢量输出的要素结构无效");
                    }
                    parser.skipChildren();
                }
            }
            throw failure("INVALID_NATIVE_METADATA", "标准矢量输出缺少要素数组");
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

        private void raster(Path input, String sourceName, JsonNode options, List<String> drivers) throws Exception {
            outputDriver("GTiff");
            Dataset source = open(input, false, drivers, new Vector<>());
            try {
                InfoOptions infoOptions = new InfoOptions(new Vector<>(List.of("-json")));
                JsonNode info;
                try { info = json.readTree(gdal.GDALInfo(source, infoOptions)); }
                finally { infoOptions.delete(); }
                String explicit = explicitCrs(options);
                String sourceCrs = explicit != null ? explicit : text(info.path("coordinateSystem").path("wkt"));
                if (sourceCrs == null) throw failure("MISSING_CRS", "栅格缺少源坐标系，须提供投影配套文件或 sourceCrs");
                // GetGeoTransform 的失败默认值也可为单位矩阵；以 GDALInfo 的真实字段判断定位是否存在。
                JsonNode transform = info.path("geoTransform");
                if (!transform.isArray() || transform.size() != 6) throw failure("MISSING_GEOLOCATION", "栅格没有仿射定位，须提供地理参考配套文件");
                for (JsonNode coefficient : transform)
                    if (!coefficient.isNumber() || !Double.isFinite(coefficient.asDouble())) throw failure("INVALID_GEOLOCATION", "栅格仿射定位包含无效数值");
                double determinant = transform.get(1).asDouble() * transform.get(5).asDouble() - transform.get(2).asDouble() * transform.get(4).asDouble();
                if (!Double.isFinite(determinant) || determinant == 0) throw failure("INVALID_GEOLOCATION", "栅格仿射定位不可逆");
                if (!info.path("bands").isArray() || info.path("bands").isEmpty()) throw failure("EMPTY_RASTER", "栅格没有可读取波段");
                Vector<String> arguments = new Vector<>(List.of("-of", "GTiff", "-co", "TILED=YES", "-co", "BIGTIFF=IF_SAFER",
                        "-co", "COPY_SRC_MDD=YES", "-mask", "auto"));
                if (explicit != null) arguments.addAll(List.of("-a_srs", sourceCrs));
                progress.accept(ProgressStage.indeterminate("NATIVE_RASTER_CONVERSION", "Java GDAL 读取原始波段、无效值与掩膜"));
                TranslateOptions translate = new TranslateOptions(arguments);
                ProgressCallback callback = callback("NATIVE_RASTER_CONVERSION", "转换栅格波段");
                Path output = directory.resolve("raster.tif");
                Dataset converted = null;
                try {
                    converted = gdal.Translate(output.toString(), source, translate, callback);
                    if (converted == null) throw failure("EMPTY_NATIVE_OUTPUT", "GDAL 没有生成标准栅格数据");
                    if (converted.Close() != gdalconstConstants.CE_None) throw failure("NATIVE_READ_FAILED", "GDAL 栅格输出关闭失败");
                } finally {
                    if (converted != null) converted.delete(); callback.delete(); translate.delete();
                }
                requireOutput(output);
                ObjectNode metadata = json.createObjectNode(); metadata.set("sourceDataset", info); metadata.set("readOptions", options);
                append("rasters", "default", sourceName, "raster.tif", geotoolsCrs(sourceCrs), info.get("cornerCoordinates"), metadata);
            } finally { source.delete(); }
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
