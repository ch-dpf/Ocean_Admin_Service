package org.ocean.terrain;

import com.gaia3d.command.TerrainerPipeline;
import com.gaia3d.terrain.tile.generation.TerrainTilingContext;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 同 JVM 地形切片入口；服务可共享，所有计算状态均由一次调用独占。 */
public final class TerrainTilingService {
    private final Semaphore permits;
    private final Duration timeout;
    private final long rasterCacheBytes;
    private final ObjectMapper objectMapper;

    public TerrainTilingService(int maxConcurrentTasks, Duration timeout, long rasterCacheBytes, ObjectMapper objectMapper) {
        if (maxConcurrentTasks < 1 || timeout == null || timeout.isNegative() || timeout.isZero()
                || rasterCacheBytes < 1024 * 1024) {
            throw new IllegalArgumentException("地形并发数、超时时间必须为正数，栅格缓存至少为 1 MiB");
        }
        this.permits = new Semaphore(maxConcurrentTasks, true);
        this.timeout = timeout;
        this.rasterCacheBytes = rasterCacheBytes;
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** 同步执行；调用方负责异步调度，取消通过执行线程的中断传入。 */
    public Result generate(Request request, Consumer<Progress> listener) throws IOException {
        Instant started = Instant.now();
        long startedNanos = System.nanoTime();
        boolean acquired = false;
        try {
            while (!(acquired = permits.tryAcquire(100, TimeUnit.MILLISECONDS))) checkDeadline(startedNanos);
            checkDeadline(startedNanos);
            Files.createDirectories(request.outputPath());
            // 文件锁防止不同服务实例向同一目录写入，锁文件不参与瓦片发布。
            try (FileChannel channel = FileChannel.open(request.outputPath().resolve(".terrain.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("地形输出目录正在被其他任务使用");
                try (TerrainTilingContext context = new TerrainTilingContext(request, startedNanos,
                        timeout, rasterCacheBytes, objectMapper, listener)) {
                    context.prepareResources();
                    new TerrainerPipeline(context).execute();
                    context.checkCancelled();
                    return new Result(request.outputPath(), Duration.between(started, Instant.now()));
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("等待地形执行资源时任务已取消");
        } catch (org.geotools.api.referencing.FactoryException
                 | org.geotools.api.referencing.operation.TransformException ex) {
            throw new IOException("地形坐标处理失败", ex);
        } finally {
            if (acquired) permits.release();
        }
    }

    private void checkDeadline(long startedNanos) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("地形任务已取消");
        if (System.nanoTime() - startedNanos >= timeout.toNanos()) throw new CancellationException("地形任务执行超时");
    }

    /** 输入为有地理定位的高程栅格，当前输出为地球 TMS quantized-mesh。 */
    public record Request(List<Path> inputPaths, Path outputPath, Path tempPath, Path logPath, Options options) {
        public Request {
            if (inputPaths == null || inputPaths.isEmpty()) throw new IllegalArgumentException("地形输入不能为空");
            inputPaths = inputPaths.stream().map(path -> path.toAbsolutePath().normalize()).toList();
            for (Path path : inputPaths) {
                if (!Files.isRegularFile(path) && !Files.isDirectory(path)) throw new IllegalArgumentException("地形输入不存在: " + path);
            }
            outputPath = Objects.requireNonNull(outputPath).toAbsolutePath().normalize();
            tempPath = tempPath == null ? outputPath.resolve("temp") : tempPath.toAbsolutePath().normalize();
            logPath = logPath == null ? null : logPath.toAbsolutePath().normalize();
            options = Objects.requireNonNull(options);
        }
    }

    public record Options(int minDepth, Integer maxDepth, String geoid, Double noDataValue,
            String interpolation, Integer rasterMaxSize, Integer mosaicSize, boolean calculateNormals,
            boolean continuePrevious, boolean leaveTemp, boolean generateLayerJson) {
        public Options {
            if (minDepth < 0 || minDepth > 22 || maxDepth != null && (maxDepth < minDepth || maxDepth > 22))
                throw new IllegalArgumentException("地形层级范围必须在 0 到 22 之间");
            if (rasterMaxSize != null && rasterMaxSize < 1 || mosaicSize != null && mosaicSize < 1)
                throw new IllegalArgumentException("栅格尺寸和镶嵌尺寸必须大于零");
            interpolation = interpolation == null ? "BILINEAR" : interpolation.toUpperCase(java.util.Locale.ROOT);
            if (!List.of("NEAREST", "BILINEAR").contains(interpolation)) throw new IllegalArgumentException("不支持的插值算法");
            geoid = geoid == null || geoid.isBlank() ? "Ellipsoid" : geoid.trim();
            if (noDataValue != null && Double.isInfinite(noDataValue)) throw new IllegalArgumentException("NoData 不能为无穷大");
        }
    }

    /** 总量未知时只回报已完成量，不推算百分比。 */
    public record Progress(String phase, String message, Long completed, Long total) {}
    public record Result(Path outputPath, Duration elapsed) {}
}
