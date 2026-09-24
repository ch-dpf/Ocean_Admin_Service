package org.ocean.admin.gis.terrain.engine.mago;

import org.ocean.admin.gis.processing.GisProcessingProgress;
import org.ocean.admin.gis.terrain.engine.TerrainProgressListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 从 mago layer.json 和已落盘 .terrain 产物计算真实工作量。 */
final class MagoTerrainProgressMonitor implements AutoCloseable {

    private static final Duration FILE_SETTLE_DELAY = Duration.ofMillis(300);
    private static final Duration STOP_WAIT = Duration.ofSeconds(3);

    private final Path outputPath;
    private final ObjectMapper objectMapper;
    private final TerrainProgressListener listener;
    private final Set<Path> completedFiles = ConcurrentHashMap.newKeySet();
    private final Map<Path, Instant> pendingFiles = new HashMap<>();

    private volatile boolean running;
    private WatchService watchService;
    private Thread monitorThread;
    private volatile long totalTiles;
    private String phase = "analyzing";
    private int lastPercent = -1;
    private String lastPublishedPhase;

    MagoTerrainProgressMonitor(
            Path outputPath,
            ObjectMapper objectMapper,
            TerrainProgressListener listener) {
        this.outputPath = outputPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.listener = listener == null ? TerrainProgressListener.NO_OP : listener;
    }

    void start() {
        publishIndeterminate("analyzing", "正在分析地形数据与瓦片范围");
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerTree(outputPath);
        } catch (IOException ex) {
            throw new MagoTerrainException("无法启动地形产物进度监控", ex);
        }
        running = true;
        monitorThread = Thread.ofVirtual()
                .name("mago-terrain-progress-monitor")
                .start(this::monitorLoop);
    }

    void onOutput(String line) {
        if (line == null) {
            return;
        }
        if (line.contains("[Pre][Standardization]") || line.contains("[Pre][Resize]")) {
            publishCurrent("preprocessing", "正在标准化和重采样地形数据");
        } else if (line.contains("[Tile]")) {
            publishCurrent("generating", "正在生成地形瓦片");
        } else if (line.contains("[Post]")) {
            publishCurrent("finalizing", "正在整理地形切片产物");
        }
    }

    void generationFinished() {
        stopMonitor();
        discoverTotalTiles();
        discoverCompletedFiles(true);
        publishCurrent("finalizing", "地形瓦片已生成，正在校验元数据");
    }

    private void monitorLoop() {
        while (running) {
            try {
                discoverTotalTiles();
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key != null) {
                    consumeEvents(key);
                }
                settleCompletedFiles();
            } catch (ClosedWatchServiceException ignored) {
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // 进度监控不影响 mago 主处理结果。
            }
        }
    }

    private void consumeEvents(WatchKey key) {
        Path directory = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                discoverCompletedFiles(false);
                continue;
            }
            Path changed = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                registerTree(changed);
            } else if (isTerrainFile(changed)) {
                pendingFiles.put(changed, Instant.now());
            } else if (changed.getFileName().toString().equals("layer.json")) {
                discoverTotalTiles();
            }
        }
        key.reset();
    }

    private void registerTree(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.forEach(path -> {
                if (Files.isDirectory(path)) {
                    registerDirectory(path);
                } else if (isTerrainFile(path)) {
                    pendingFiles.put(path.toAbsolutePath().normalize(), Instant.now());
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // 刚刚创建的临时目录可能已被 mago 移除，下一次事件继续处理。
        }
    }

    private void registerDirectory(Path directory) {
        try {
            directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | ClosedWatchServiceException ignored) {
            // 目录注册失败时保持主切片任务运行。
        }
    }

    private void settleCompletedFiles() {
        Instant threshold = Instant.now().minus(FILE_SETTLE_DELAY);
        Set<Path> settled = new HashSet<>();
        for (Map.Entry<Path, Instant> entry : pendingFiles.entrySet()) {
            Path path = entry.getKey();
            if (entry.getValue().isAfter(threshold) || !isCompletedFile(path)) {
                continue;
            }
            settled.add(path);
        }
        if (settled.isEmpty()) {
            return;
        }
        pendingFiles.keySet().removeAll(settled);
        if (completedFiles.addAll(settled)) {
            publishCurrent("generating", progressMessage());
        }
    }

    private void discoverCompletedFiles(boolean includeUnsettled) {
        if (!Files.isDirectory(outputPath)) {
            return;
        }
        try (var paths = Files.walk(outputPath)) {
            paths.filter(this::isTerrainFile)
                    .filter(path -> includeUnsettled ? isWrittenFile(path) : isCompletedFile(path))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(completedFiles::add);
        } catch (IOException | RuntimeException ignored) {
            // 由任务最终产物校验判定文件系统错误。
        }
    }

    private boolean isCompletedFile(Path path) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return isWrittenFile(path)
                    && modified.toInstant().isBefore(Instant.now().minus(FILE_SETTLE_DELAY));
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean isWrittenFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean isTerrainFile(Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().endsWith(".terrain");
    }

    private void discoverTotalTiles() {
        if (totalTiles > 0) {
            return;
        }
        Path layerJson = outputPath.resolve("layer.json");
        if (!Files.isRegularFile(layerJson)) {
            return;
        }
        try {
            JsonNode available = objectMapper.readTree(layerJson.toFile()).get("available");
            if (available == null || !available.isArray()) {
                return;
            }
            long discovered = 0;
            for (JsonNode depth : available) {
                if (!depth.isArray()) {
                    continue;
                }
                for (JsonNode range : depth) {
                    if (!range.has("startX") || !range.has("endX")
                            || !range.has("startY") || !range.has("endY")) {
                        continue;
                    }
                    long width = range.path("endX").asLong() - range.path("startX").asLong() + 1;
                    long height = range.path("endY").asLong() - range.path("startY").asLong() + 1;
                    if (width > 0 && height > 0) {
                        discovered = Math.addExact(discovered, Math.multiplyExact(width, height));
                    }
                }
            }
            if (discovered > 0) {
                totalTiles = discovered;
                publishCurrent(phase, progressMessage());
            }
        } catch (Exception ignored) {
            // layer.json 写入完成前可能暂时无法解析，后续轮询会重试。
        }
    }

    private synchronized void publishCurrent(String newPhase, String message) {
        phase = newPhase;
        if (totalTiles <= 0) {
            publishIndeterminate(newPhase, message);
            return;
        }
        long completed = Math.min(completedFiles.size(), totalTiles);
        GisProcessingProgress progress = GisProcessingProgress.determinate(
                newPhase, completed, totalTiles, message);
        int percent = progress.processingPercent();
        if (percent == lastPercent && newPhase.equals(lastPublishedPhase)) {
            return;
        }
        lastPercent = percent;
        lastPublishedPhase = newPhase;
        emit(progress);
    }

    private synchronized void publishIndeterminate(String newPhase, String message) {
        phase = newPhase;
        if (newPhase.equals(lastPublishedPhase)) {
            return;
        }
        lastPublishedPhase = newPhase;
        emit(GisProcessingProgress.indeterminate(newPhase, message));
    }

    private String progressMessage() {
        return "正在生成地形瓦片：" + completedFiles.size() + "/" + totalTiles + "张";
    }

    private void emit(GisProcessingProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException ignored) {
            // 进度消费者不能中断地形引擎。
        }
    }

    private void stopMonitor() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 已停止的监控器无需再处理关闭错误。
            }
        }
        if (monitorThread != null) {
            try {
                monitorThread.join(STOP_WAIT);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stopMonitor();
    }
}
