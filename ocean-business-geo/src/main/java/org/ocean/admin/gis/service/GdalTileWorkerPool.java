package org.ocean.admin.gis.service;

import jakarta.annotation.PreDestroy;
import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** 交互读取复用独立原生进程；每个进程一次只执行一个请求，超时后销毁。 */
@Component
public class GdalTileWorkerPool {
    private final GisProcessingProperties processing;
    private final VectorTileProperties tiles;
    private final ObjectMapper json;
    private final Semaphore permits;
    private final ArrayBlockingQueue<Worker> idle;
    private final Set<Worker> workers = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
    public GdalTileWorkerPool(GisProcessingProperties processing, VectorTileProperties tiles, ObjectMapper json) {
        this(processing, tiles, json, tiles.getMaxConcurrentQueries());
    }

    GdalTileWorkerPool(GisProcessingProperties processing, VectorTileProperties tiles, ObjectMapper json, int concurrency) {
        this.processing = processing;
        this.tiles = tiles;
        this.json = json;
        permits = new Semaphore(concurrency, true);
        idle = new ArrayBlockingQueue<>(concurrency);
    }

    public JsonNode execute(ObjectNode request, Function<Path, List<String>> command) {
        boolean acquired = false;
        Worker worker = null;
        try {
            acquired = permits.tryAcquire(tiles.getQueueWaitMs(), TimeUnit.MILLISECONDS);
            if (!acquired || closed) throw new IOException("GPKG 读取队列已满或服务正在关闭");
            worker = idle.poll();
            if (worker == null || !worker.process.isAlive()) {
                if (worker != null) discard(worker);
                worker = start(command);
            }
            request.put("version", processing.getGdalVersion());
            request.put("maxMetadataBytes", processing.getGdalMaxMetadataBytes());
            Path resultFile = worker.directory.resolve("result.json");
            Files.deleteIfExists(resultFile);
            json.writeValue(worker.directory.resolve("request.json").toFile(), request);
            worker.input.write("execute\n");
            worker.input.flush();
            Worker current = worker;
            var response = io.submit(() -> {
                String line = current.output.readLine();
                if (!"complete".equals(line)) throw new IOException("GPKG 工作进程通信中断");
                if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > 16 * 1024 * 1024)
                    throw new IOException("GPKG 工作进程结果缺失或超限");
                return json.readTree(resultFile.toFile());
            });
            JsonNode result;
            try { result = response.get(30, TimeUnit.SECONDS); }
            finally { if (!response.isDone()) response.cancel(true); }
            if (result.has("error")) throw new IOException(result.path("error").path("message").asText());
            if (closed) discard(worker);
            else idle.offer(worker);
            worker = null;
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GPKG 读取已中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("GPKG 读取失败: " + ex.getMessage(), ex);
        } finally {
            if (worker != null) discard(worker);
            if (acquired) permits.release();
        }
    }

    private synchronized Worker start(Function<Path, List<String>> command) throws IOException {
        if (closed) throw new IOException("GPKG 读取服务正在关闭");
        Path root = Path.of(processing.getBasePath()).toAbsolutePath().normalize().resolve("tile-workers");
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "worker-");
        try {
            var args = new java.util.ArrayList<>(command.apply(directory.resolve("request.json")));
            args.add("--serve");
            ProcessBuilder builder = new ProcessBuilder(args).directory(directory.toFile());
            builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("LOADER_")
                    || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key.toUpperCase(Locale.ROOT)));
            builder.environment().put("PROJ_NETWORK", "OFF");
            builder.environment().put("CPL_TMPDIR", directory.toString());
            builder.environment().put("GDAL_HTTP_TIMEOUT", "1");
            builder.environment().put("CPL_VSIL_CURL_ALLOWED_EXTENSIONS", ".ocean-disabled-network");
            String nativePath = processing.getGdalNativeLibraryPath();
            if (nativePath != null && !nativePath.isBlank())
                builder.environment().put("PATH", nativePath + java.io.File.pathSeparator + builder.environment().getOrDefault("PATH", ""));
            Process process = builder.start();
            Worker worker = new Worker(directory, process,
                    process.outputWriter(StandardCharsets.UTF_8), process.inputReader(StandardCharsets.UTF_8));
            workers.add(worker);
            // 原生警告不能阻塞管道；错误详情由有界 result.json 返回。
            io.submit(() -> {
                try (var errors = process.getErrorStream()) { errors.transferTo(java.io.OutputStream.nullOutputStream()); }
                return null;
            });
            return worker;
        } catch (RuntimeException | IOException ex) {
            Files.deleteIfExists(directory);
            throw ex;
        }
    }

    private void discard(Worker worker) {
        workers.remove(worker);
        worker.process.destroyForcibly();
        try {
            worker.process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (!worker.process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("原生进程尚未退出");
            worker.input.close();
            worker.output.close();
            worker.process.getErrorStream().close();
            Path root = Path.of(processing.getBasePath()).toAbsolutePath().normalize().resolve("tile-workers");
            if (!worker.directory.toAbsolutePath().normalize().getParent().equals(root))
                throw new IOException("工作进程清理目录越界");
            // Windows 在进程退出后可能稍晚才释放工作目录句柄。
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    // GeoTools 会在工作目录建立 EPSG 缓存，进程退出后连同请求文件一起清理；不跟随符号链接。
                    Files.walkFileTree(worker.directory, new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                            Files.delete(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                            if (error != null) throw error;
                            Files.delete(directory);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
                    break;
                }
                catch (java.nio.file.FileSystemException ex) {
                    if (attempt == 4) throw ex;
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (IOException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("瓦片进程临时目录清理失败: {}", worker.directory, ex);
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        idle.clear();
        workers.forEach(this::discard);
        io.shutdownNow();
    }

    private record Worker(Path directory, Process process, BufferedWriter input, BufferedReader output) { }
}
