package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.dto.TaskProgressUnit;
import org.ocean.admin.gis.dto.TaskProgressUpdate;
import org.ocean.admin.gis.dto.TaskStage;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.entity.GisTileSetArchive;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisTileSetMapper;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

/** 在独立线程中把 READY 静态瓦片集打包为可下载 ZIP。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TileSetArchiveWorker {
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final long PROGRESS_INTERVAL_NANOS = 1_000_000_000L;

    private final TileSetArchiveService archiveService;
    private final ProcessingTaskRecordService taskService;
    private final GisTileSetMapper tileSetMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisProcessingStorageService storageService;
    private final AsyncTaskService asyncTaskService;
    private final GisProcessingProperties properties;
    private final ObjectMapper objectMapper;

    @Async("archiveExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleArchiveRequested(
            TileSetArchiveService.TileSetArchiveRequestedEvent event) {
        log.info("开始处理静态瓦片集归档事件: archiveId={}", event.archiveId());
        GisTileSetArchive archive = archiveService.claimRunning(event.archiveId());
        if (archive == null) {
            log.info("静态瓦片集归档事件已忽略: archiveId={}, reason=NOT_QUEUED",
                    event.archiveId());
            return;
        }
        Path target = storageService.resolveArchive(
                storageService.archiveStorageKey(archive.getArchiveNo()));
        Path temporary = storageService.resolveArchiveTemporary(archive.getArchiveNo());
        boolean archiveCommitted = false;
        boolean progressRegistered = false;
        try {
            GisProcessingTask task = taskService.getRequired(archive.getProcessingTaskId());
            GisTileSet tileSet = tileSetMapper.selectById(archive.getTileSetId());
            validateSource(task, tileSet, archive);
            Path tilesRoot = storageService.resolveTiles(tileSet.getOutputKey());
            log.info("开始扫描静态瓦片集归档源文件: archiveNo={}, processingTaskId={}, "
                            + "taskNo={}, tileSetId={}",
                    archive.getArchiveNo(), task.getId(), task.getTaskNo(), tileSet.getId());
            List<Path> files = scanFiles(tilesRoot);
            long totalBytes = totalBytes(files);
            log.info("静态瓦片集归档源文件扫描完成: archiveNo={}, totalFiles={}, totalBytes={}",
                    archive.getArchiveNo(), files.size(), totalBytes);
            archiveService.initializeProgress(archive.getId(), files.size(), totalBytes);

            asyncTaskService.registerTask(
                    archive.getArchiveNo(), task.getTaskNo(), archive.getArchiveName(),
                    files.size(), "GIS_TILE_SET_ARCHIVE", TaskProgressUnit.FILE);
            progressRegistered = true;
            asyncTaskService.updateProgress(archive.getArchiveNo(),
                    TaskProgressUpdate.stageChanged(TaskStage.determinate(
                            "packing", "正在打包静态瓦片集", 0, files.size(),
                            TaskProgressUnit.FILE)),
                    TaskProgressUpdate.overallProgressChanged(0));
            log.info("静态瓦片集归档实时进度任务已注册: archiveNo={}, "
                            + "correlationTaskNo={}, totalFiles={}",
                    archive.getArchiveNo(), task.getTaskNo(), files.size());

            Files.createDirectories(target.getParent());
            if (Files.isSymbolicLink(target.getParent())) {
                throw new IllegalStateException("静态瓦片集归档目录不能是符号链接");
            }
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(target);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ArchiveProgress progress = writeArchive(
                    temporary, tilesRoot, files, totalBytes, archive, task, tileSet, digest);
            if (progress.processedFiles() != files.size()
                    || progress.processedBytes() != totalBytes) {
                throw new IllegalStateException("归档期间静态瓦片集内容发生变化");
            }
            moveAtomically(temporary, target);
            long archiveSize = Files.size(target);
            String sha256 = HexFormat.of().formatHex(digest.digest());
            String storageKey = storageService.archiveStorageKey(archive.getArchiveNo());

            archiveService.markCompleted(archive.getId(), storageKey, archiveSize, sha256,
                    files.size(), totalBytes);
            archiveCommitted = true;
            asyncTaskService.updateProgress(archive.getArchiveNo(),
                    TaskProgressUpdate.stageChanged(TaskStage.determinate(
                            "completed", "静态瓦片集归档完成", files.size(), files.size(),
                            TaskProgressUnit.FILE)),
                    TaskProgressUpdate.overallProgressChanged(100),
                    TaskProgressUpdate.resultChanged(files.size(), 0));
            asyncTaskService.finalizeTaskResult(
                    archive.getArchiveNo(), "静态瓦片集归档完成");
            log.info("静态瓦片集归档完成: archiveNo={}, tileSetId={}, files={}, sizeBytes={}",
                    archive.getArchiveNo(), archive.getTileSetId(), files.size(), archiveSize);
        } catch (Exception ex) {
            if (archiveCommitted) {
                log.warn("静态瓦片集归档已完成，但进度收口失败: archiveNo={}",
                        archive.getArchiveNo(), ex);
                return;
            }
            deleteQuietly(temporary);
            deleteQuietly(target);
            archiveService.markFailed(archive.getId(), ex.getMessage());
            if (progressRegistered) {
                asyncTaskService.finalizeTaskFailure(
                        archive.getArchiveNo(), "静态瓦片集归档失败: " + ex.getMessage());
            }
            log.error("静态瓦片集归档失败: archiveNo={}, tileSetId={}",
                    archive.getArchiveNo(), archive.getTileSetId(), ex);
        }
    }

    private ArchiveProgress writeArchive(
            Path temporary, Path tilesRoot, List<Path> files, long totalBytes,
            GisTileSetArchive archive, GisProcessingTask task, GisTileSet tileSet,
            MessageDigest digest) throws Exception {
        long processedBytes = 0L;
        long processedFiles = 0L;
        long lastProgressTime = System.nanoTime();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream fileOutput = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                DigestOutputStream digestOutput = new DigestOutputStream(
                        new BufferedOutputStream(fileOutput, BUFFER_SIZE), digest);
                ZipOutputStream zipOutput = new ZipOutputStream(digestOutput)) {
            zipOutput.setLevel(properties.getArchiveCompressionLevel());
            writeManifest(zipOutput, archive, task, tileSet, files.size(), totalBytes);
            for (Path file : files) {
                String relativeName = tilesRoot.relativize(file).toString()
                        .replace(file.getFileSystem().getSeparator(), "/");
                ZipEntry entry = new ZipEntry("tiles/" + relativeName);
                entry.setTime(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                        .toMillis());
                zipOutput.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        zipOutput.write(buffer, 0, read);
                        processedBytes = Math.addExact(processedBytes, read);
                    }
                }
                zipOutput.closeEntry();
                processedFiles++;
                long now = System.nanoTime();
                if (processedFiles == files.size()
                        || now - lastProgressTime >= PROGRESS_INTERVAL_NANOS) {
                    reportProgress(archive, processedFiles, processedBytes,
                            files.size(), totalBytes);
                    lastProgressTime = now;
                }
            }
        }
        return new ArchiveProgress(processedFiles, processedBytes);
    }

    private void writeManifest(
            ZipOutputStream zipOutput, GisTileSetArchive archive,
            GisProcessingTask task, GisTileSet tileSet,
            long totalFileCount, long totalBytes) throws IOException {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("archiveNo", archive.getArchiveNo());
        manifest.put("generatedAt", LocalDateTime.now().toString());

        ObjectNode taskNode = objectMapper.createObjectNode();
        taskNode.put("id", task.getId());
        taskNode.put("taskNo", task.getTaskNo());
        taskNode.put("taskName", task.getTaskName());
        taskNode.put("processingType", task.getProcessingType().name());
        try {
            taskNode.set("parameters", objectMapper.readTree(task.getParametersJson()));
        } catch (Exception ex) {
            throw new IOException("处理任务参数快照无法写入归档清单", ex);
        }
        manifest.set("task", taskNode);

        ObjectNode tileSetNode = objectMapper.createObjectNode();
        tileSetNode.put("id", tileSet.getId());
        putNullable(tileSetNode, "targetCrs", tileSet.getTargetCrs());
        putNullable(tileSetNode, "tileProfile", tileSet.getTileProfile());
        putNullable(tileSetNode, "outputFormat", tileSet.getOutputFormat());
        putNullable(tileSetNode, "minZoom", tileSet.getMinZoom());
        putNullable(tileSetNode, "maxZoom", tileSet.getMaxZoom());
        manifest.set("tileSet", tileSetNode);

        ArrayNode inputsNode = objectMapper.createArrayNode();
        for (GisProcessingInput input : inputMapper.selectByTaskId(task.getId())) {
            ObjectNode inputNode = objectMapper.createObjectNode();
            inputNode.put("sequenceNo", input.getSequenceNo());
            inputNode.put("originalName", input.getOriginalName());
            putNullable(inputNode, "sizeBytes", input.getSizeBytes());
            putNullable(inputNode, "sha256", input.getSha256());
            inputsNode.add(inputNode);
        }
        manifest.set("inputs", inputsNode);
        manifest.put("totalFileCount", totalFileCount);
        manifest.put("totalBytes", totalBytes);

        ZipEntry manifestEntry = new ZipEntry("export-manifest.json");
        zipOutput.putNextEntry(manifestEntry);
        zipOutput.write(objectMapper.writeValueAsBytes(manifest));
        zipOutput.closeEntry();
    }

    private void reportProgress(
            GisTileSetArchive archive, long processedFiles, long processedBytes,
            long totalFiles, long totalBytes) {
        archiveService.updateProgress(archive.getId(), processedFiles, processedBytes);
        int percent = totalBytes > 0
                ? (int) Math.min(100, (double) processedBytes * 100 / totalBytes)
                : (int) Math.min(100, (double) processedFiles * 100 / totalFiles);
        asyncTaskService.updateProgress(archive.getArchiveNo(),
                TaskProgressUpdate.stageChanged(TaskStage.determinate(
                        "packing", "正在打包静态瓦片集", processedFiles, totalFiles,
                        TaskProgressUnit.FILE)),
                TaskProgressUpdate.overallProgressChanged(percent),
                TaskProgressUpdate.resultChanged(processedFiles, 0));
        log.debug("静态瓦片集归档进度已上报: archiveNo={}, processedFiles={}, "
                        + "totalFiles={}, processedBytes={}, totalBytes={}, percent={}",
                archive.getArchiveNo(), processedFiles, totalFiles, processedBytes,
                totalBytes, percent);
    }

    private List<Path> scanFiles(Path tilesRoot) throws IOException {
        if (Files.isSymbolicLink(tilesRoot)) {
            throw new IllegalStateException("静态瓦片集根目录不能是符号链接");
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(tilesRoot)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("静态瓦片集包含符号链接: " + path);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(path);
                }
            }
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("静态瓦片集目录中没有可归档文件");
        }
        files.sort(Comparator.comparing(path -> tilesRoot.relativize(path).toString()));
        return files;
    }

    private long totalBytes(List<Path> files) throws IOException {
        long total = 0L;
        for (Path file : files) {
            total = Math.addExact(total, Files.size(file));
        }
        return total;
    }

    private void validateSource(
            GisProcessingTask task, GisTileSet tileSet, GisTileSetArchive archive) {
        if (tileSet == null || !archive.getProcessingTaskId().equals(tileSet.getTaskId())
                || !"READY".equals(tileSet.getTileSetStatus())) {
            throw new IllegalStateException("归档关联的静态瓦片集不存在或尚未就绪");
        }
        if (task.getTaskStatus() != org.ocean.admin.gis.dto.TaskStatus.COMPLETED) {
            throw new IllegalStateException("归档关联的处理任务不再处于 COMPLETED 状态");
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            log.warn("归档文件系统不支持原子移动，改用覆盖移动: target={}", target);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            log.warn("清理归档临时文件失败: path={}", path, cleanupFailure);
        }
    }

    private void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private void putNullable(ObjectNode node, String name, Number value) {
        if (value == null) {
            node.putNull(name);
        } else if (value instanceof Integer integer) {
            node.put(name, integer);
        } else {
            node.put(name, value.longValue());
        }
    }

    private record ArchiveProgress(long processedFiles, long processedBytes) {
    }
}
