/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.generation;

import com.gaia3d.terrain.tile.custom.AvailableTileSet;
import com.gaia3d.terrain.tile.geotiff.GeoTiffCoverageStore;
import com.gaia3d.terrain.types.InterpolationType;
import com.gaia3d.terrain.types.PriorityType;
import com.gaia3d.util.CelestialBody;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.ocean.terrain.TerrainTilingService;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** 一次任务独占的上下文；禁止通过静态变量或线程局部变量传递任务状态。 */
@Getter
@Slf4j
public final class TerrainTilingContext implements AutoCloseable {
    private final TerrainTilingService.Request request;
    private final long startedNanos;
    private final long timeoutNanos;
    private final long rasterCacheBytes;
    private final ObjectMapper objectMapper;
    private final Consumer<TerrainTilingService.Progress> listener;
    private final List<String> inputPaths;
    @Setter private String inputPath;
    private final String outputPath;
    private final int minimumTileDepth;
    @Setter private int maximumTileDepth;
    private final InterpolationType interpolationType;
    private final PriorityType priorityType = PriorityType.RESOLUTION;
    private final double noDataValue;
    private final double intensity = 4.0;
    private final int mosaicSize;
    private final int maxRasterSize;
    private final boolean calculateNormalsExtension;
    private final boolean metaDataExtension = false;
    private final boolean waterMaskExtension = false;
    private final boolean leaveTemp;
    private final boolean isContinue;
    private final boolean isModify = false;
    private final CelestialBody celestialBody = CelestialBody.EARTH;
    private final CoordinateReferenceSystem outputCRS = DefaultGeographicCRS.WGS84;
    private final int rasterTileSize = 256;
    private final String imageryType = "CRS84";
    @Setter private boolean originIsLeftUp;
    private String geoidPath;
    private Path ownedTempPath;
    private String tileTempPath;
    private String standardizeTempPath;
    private String resizedTiffTempPath;
    private String splitTiffTempPath;
    private BufferedWriter progressLog;
    private long completedTiles;
    private final Map<Integer, List<File>> depthGeoTiffFilesMap = new HashMap<>();
    private final Map<Integer, Double> depthDesiredPixelSizeXinMetersMap = new HashMap<>();
    private final Map<String, String> mapNoUsableGeotiffPaths = new HashMap<>();
    private final List<File> standardizedGeoTiffFiles = new ArrayList<>();
    private final AvailableTileSet availableTileSet = new AvailableTileSet();
    private final GeoTiffCoverageStore geoTiffCoverageStore;
    @Setter private int geoTiffFilesCount;

    public TerrainTilingContext(TerrainTilingService.Request request, long startedNanos, Duration timeout,
            long rasterCacheBytes, ObjectMapper objectMapper, Consumer<TerrainTilingService.Progress> listener) {
        this.request = request;
        this.startedNanos = startedNanos;
        this.timeoutNanos = timeout.toNanos();
        this.rasterCacheBytes = rasterCacheBytes;
        this.objectMapper = objectMapper;
        this.listener = listener == null ? ignored -> {} : listener;
        inputPaths = request.inputPaths().stream().map(Path::toString).toList();
        inputPath = inputPaths.getFirst();
        outputPath = request.outputPath().toString();
        var options = request.options();
        minimumTileDepth = options.minDepth();
        maximumTileDepth = options.maxDepth() == null ? -1 : options.maxDepth();
        interpolationType = InterpolationType.valueOf(options.interpolation());
        noDataValue = options.noDataValue() == null ? -9999d : options.noDataValue();
        mosaicSize = options.mosaicSize() == null ? 8 : options.mosaicSize();
        maxRasterSize = options.rasterMaxSize() == null ? 4096 : options.rasterMaxSize();
        calculateNormalsExtension = options.calculateNormals();
        leaveTemp = options.leaveTemp();
        isContinue = options.continuePrevious();
        geoTiffCoverageStore = new GeoTiffCoverageStore(this);
        for (int depth = 0; depth <= 28; depth++) {
            depthDesiredPixelSizeXinMetersMap.put(depth,
                    com.gaia3d.terrain.util.GeographicTerrainTileUtils.getTileSizeInMetersByDepth(depth) / 256d);
        }
    }

    public void prepareResources() throws IOException {
        checkCancelled();
        Files.createDirectories(request.tempPath());
        ownedTempPath = Files.createTempDirectory(request.tempPath(), "terrain-");
        tileTempPath = ownedTempPath.resolve("tiles").toString();
        standardizeTempPath = ownedTempPath.resolve("standardization").toString();
        resizedTiffTempPath = ownedTempPath.resolve("resized").toString();
        splitTiffTempPath = ownedTempPath.resolve("split").toString();
        if (request.logPath() != null) {
            Files.createDirectories(request.logPath().getParent());
            progressLog = Files.newBufferedWriter(request.logPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        String geoid = request.options().geoid();
        String resource = switch (geoid.toUpperCase(Locale.ROOT)) {
            case "EGM96" -> "egm96_15.tif";
            case "EGM2008" -> "egm2008_2_5.tif";
            case "EGM84" -> "egm84_30.tif";
            default -> null;
        };
        if (resource != null) {
            Path target = ownedTempPath.resolve(resource);
            InputStream resourceStream = getClass().getResourceAsStream("/geoid/" + resource);
            if (resourceStream == null) throw new IOException("缺少高程基准资源: " + resource);
            try (BufferedInputStream input = new BufferedInputStream(resourceStream)) {
                input.mark(4);
                byte[] header = input.readNBytes(4);
                if (header.length != 4 || !((header[0] == 'I' && header[1] == 'I' && header[2] == 42 && header[3] == 0)
                        || (header[0] == 'M' && header[1] == 'M' && header[2] == 0 && header[3] == 42))) {
                    throw new IOException("高程基准资源无效，请在构建前执行 git lfs pull: " + resource);
                }
                input.reset();
                Files.copy(input, target);
            }
            geoidPath = target.toString();
        } else if (!geoid.equalsIgnoreCase("Ellipsoid")) {
            Path path = Path.of(geoid).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) throw new IOException("高程基准文件不存在: " + path);
            geoidPath = path.toString();
        }
    }

    public boolean originIsLeftUp() { return originIsLeftUp; }

    /** 在有界计算块之间协作取消，底层读取返回后再次检查。 */
    public void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("地形任务已取消");
        if (System.nanoTime() - startedNanos >= timeoutNanos) throw new CancellationException("地形任务执行超时");
    }

    public void phase(String phase, String message) {
        checkCancelled();
        report(new TerrainTilingService.Progress(phase, message, null, null));
    }

    public void tileWritten() {
        checkCancelled();
        report(new TerrainTilingService.Progress("generating", "正在生成地形瓦片", ++completedTiles, null));
    }

    private void report(TerrainTilingService.Progress progress) {
        if (progressLog != null) {
            try {
                progressLog.write(progress.phase() + " " + progress.message()
                        + (progress.completed() == null ? "" : " " + progress.completed()));
                progressLog.newLine();
                progressLog.flush();
            } catch (IOException ex) { throw new UncheckedIOException("无法写入地形任务日志", ex); }
        }
        try { listener.accept(progress); }
        catch (RuntimeException ex) { log.warn("地形进度回调失败", ex); }
    }

    @Override public void close() throws IOException {
        try { geoTiffCoverageStore.clear(); }
        finally {
            try { if (progressLog != null) progressLog.close(); }
            finally {
                // 只清理由当前调用创建的独占目录，保留任务输出和用户输入。
                if (!leaveTemp && ownedTempPath != null)
                    org.apache.commons.io.FileUtils.deleteDirectory(ownedTempPath.toFile());
            }
        }
    }
}
