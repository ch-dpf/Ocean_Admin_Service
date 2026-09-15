package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/** 服务器任意目录地形切片的校验与异步任务编排。 */
@Service
@RequiredArgsConstructor
public class GisFolderProcessingService {

    private final GisProcessingStorageService processingStorageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisFolderProcessingTaskService transactionService;
    private final GisFolderProcessingWorker processingWorker;
    private final GisTaskService gisTaskService;
    private final TaskProgressService taskProgressService;

    public GisProcessingTaskVO submit(String folderPath) {
        Path inputFolder = validateFolder(folderPath);
        engineRegistry.require(GisProcessingType.TERRAIN);
        String taskNo = generateTaskNo();
        GisProcessingWorkspace workspace = processingStorageService.folderWorkspace(
                GisProcessingType.TERRAIN, taskNo);
        GisFolderProcessingExecution execution = transactionService.create(
                taskNo, inputFolder, workspace);
        try {
            taskProgressService.registerTask(
                    taskNo,
                    "地形目录处理：" + displayName(inputFolder),
                    1,
                    "GIS_TERRAIN");
            processingWorker.process(execution);
        } catch (Exception ex) {
            gisTaskService.markFailed(execution.taskId(), ex.getMessage());
            taskProgressService.finalizeTaskFailure(
                    taskNo, "文件夹切片任务启动失败: " + ex.getMessage());
            throw ex;
        }

        return GisProcessingTaskVO.builder()
                .taskId(execution.taskId())
                .taskNo(taskNo)
                .processingType(GisProcessingType.TERRAIN.name())
                .outputKey(workspace.outputKey())
                .status("QUEUED")
                .build();
    }

    private Path validateFolder(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            throw new IllegalArgumentException("文件夹路径不能为空");
        }
        final Path folder;
        try {
            folder = Path.of(folderPath.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("非法文件夹路径: " + folderPath, ex);
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("文件夹不存在或不是目录: " + folderPath);
        }
        if (!Files.isReadable(folder)) {
            throw new IllegalArgumentException("文件夹不可读: " + folderPath);
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            if (paths.noneMatch(Files::isRegularFile)) {
                throw new IllegalArgumentException("文件夹下没有可处理的文件: " + folderPath);
            }
        } catch (IOException | UncheckedIOException ex) {
            throw new IllegalArgumentException("无法读取文件夹: " + folderPath, ex);
        }
        return folder;
    }

    private String generateTaskNo() {
        return "GIS_TERRAIN_FOLDER_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }

    static String displayName(Path folder) {
        Path fileName = folder.getFileName();
        return fileName == null ? folder.toString() : fileName.toString();
    }
}
