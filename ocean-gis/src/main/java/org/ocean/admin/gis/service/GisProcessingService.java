package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisFileProcessRequest;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.ocean.admin.gis.vo.GisProcessingTaskFileVO;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 单文件、服务器目录和多文件 GIS 切片任务编排。 */
@Service
@RequiredArgsConstructor
public class GisProcessingService {
    private static final int MAX_FILES = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;

    private final GisFileMetaService gisFileMetaService;
    private final GisDataSetMapper gisDataSetMapper;
    private final FileUploadUtil fileUploadUtil;
    private final GisProcessingStorageService processingStorageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisProcessingTaskService transactionService;
    private final GisProcessingWorker worker;
    private final GisTaskLifecycleService taskLifecycle;
    private final GisTaskService gisTaskService;
    private final GisProcessingTaskFileMapper fileMapper;

    public GisProcessingTaskVO submitSingle(Long fileMetaId, GisFileProcessRequest request) {
        if (request == null || request.getProcessingType() == null) {
            throw new IllegalArgumentException("处理类型不能为空");
        }
        GisProcessingType processingType = request.getProcessingType();
        GisFileMeta fileMeta = validateFile(fileMetaId, processingType);
        Path inputPath = fileUploadUtil.resolveStoredPath(fileMeta.getStorageKey());
        GisFileProcessingEngine engine = engineRegistry.require(processingType);
        engine.validate(fileMeta, inputPath);

        String taskNo = GisTaskFactory.generateTaskNo("GIS_" + processingType.name());
        GisProcessingWorkspace workspace = processingStorageService.workspace(
                processingType, fileMeta.getId(), taskNo);
        GisProcessingExecution execution = transactionService.createSingle(
                taskNo, fileMeta, processingType, inputPath, workspace);
        taskLifecycle.dispatch(execution.taskId(), taskNo,
                processingType.displayName() + "：" + fileMeta.getOriginalName(),
                1, "GIS_" + processingType.name(),
                () -> worker.process(execution), "切片任务启动失败: ");

        return GisProcessingTaskVO.builder()
                .taskId(execution.taskId())
                .taskNo(taskNo)
                .fileMetaId(fileMeta.getId())
                .dataSetId(fileMeta.getDataSetId())
                .processingType(processingType.name())
                .outputKey(workspace.outputKey())
                .status("QUEUED")
                .build();
    }

    public GisProcessingTaskVO submitFolder(String folderPath) {
        Path inputFolder = validateFolder(folderPath);
        engineRegistry.require(GisProcessingType.TERRAIN);
        String taskNo = GisTaskFactory.generateTaskNo("GIS_TERRAIN_FOLDER");
        GisProcessingWorkspace workspace = processingStorageService.folderWorkspace(
                GisProcessingType.TERRAIN, taskNo);
        GisFolderProcessingExecution execution = transactionService.createFolder(
                taskNo, inputFolder, workspace);
        taskLifecycle.dispatch(execution.taskId(), taskNo,
                "地形目录处理：" + displayName(inputFolder), 1, "GIS_TERRAIN",
                () -> worker.process(execution), "文件夹切片任务启动失败: ");

        return GisProcessingTaskVO.builder()
                .taskId(execution.taskId())
                .taskNo(taskNo)
                .processingType(GisProcessingType.TERRAIN.name())
                .outputKey(workspace.outputKey())
                .status("QUEUED")
                .build();
    }

    public List<GisProcessingTaskFileVO> getBatchFiles(Long taskId) {
        gisTaskService.getRequired(taskId);
        return fileMapper.selectByTaskId(taskId).stream()
                .map(file -> new GisProcessingTaskFileVO(file.getId(), file.getFileIndex(),
                        file.getOriginalName(), file.getOutputKey(), file.getStatus(),
                        file.getErrorMessage()))
                .toList();
    }

    public GisBatchProcessingTaskVO submitBatch(GisCreateProcessingTaskRequest request,
            List<MultipartFile> files) {
        validateBatchRequest(request, files);
        GisFileProcessingEngine engine = engineRegistry.require(request.processingType());
        validateParameters(request.processingType(), request.parameters());
        String taskNo = GisTaskFactory.generateTaskNo(
                "GIS_" + request.processingType().name() + "_BATCH");
        GisProcessingWorkspace workspace = processingStorageService.batchWorkspace(
                request.processingType(), taskNo);
        List<GisStagedFile> staged = new ArrayList<>(files.size());
        List<GisStoredFile> stored = new ArrayList<>(files.size());
        try {
            for (MultipartFile file : files) {
                GisStagedFile stagedFile = fileUploadUtil.stage(taskNo, file);
                staged.add(stagedFile);
                GisFileMeta meta = new GisFileMeta();
                meta.setExtension(stagedFile.getExtension());
                meta.setOriginalName(stagedFile.getOriginalName());
                engine.validate(meta, fileUploadUtil.resolveStoredPath(stagedFile.getStagingKey()));
            }
            for (GisStagedFile stagedFile : staged) {
                stored.add(fileUploadUtil.commitForProcessing(taskNo, stagedFile));
            }
            GisBatchProcessingExecution execution = transactionService.createBatch(
                    taskNo, request, workspace, staged, stored);
            taskLifecycle.dispatch(execution.taskId(), taskNo, request.taskName().trim(),
                    stored.size(), "GIS_" + request.processingType().name(),
                    () -> worker.process(execution), "处理任务启动失败: ");
            return GisBatchProcessingTaskVO.builder()
                    .taskId(execution.taskId())
                    .taskNo(taskNo)
                    .processingType(request.processingType().name())
                    .outputKey(workspace.outputKey())
                    .totalCount(stored.size())
                    .status("QUEUED")
                    .build();
        } catch (Exception ex) {
            stored.forEach(file -> deleteStoredQuietly(file.getStorageKey()));
            staged.forEach(file -> deleteStagedQuietly(file.getStagingKey()));
            throw ex;
        }
    }

    private GisFileMeta validateFile(Long fileMetaId, GisProcessingType processingType) {
        GisFileMeta fileMeta = gisFileMetaService.getRequiredEntity(fileMetaId);
        if (!"READY".equals(fileMeta.getUploadStatus())) {
            throw new IllegalStateException("只有 READY 状态的已入库文件可以处理");
        }
        if (!"LOCAL".equals(fileMeta.getStorageType())) {
            throw new IllegalStateException("当前仅支持处理 LOCAL 存储中的文件");
        }
        GisDataSet dataSet = gisDataSetMapper.selectById(fileMeta.getDataSetId());
        if (dataSet == null) {
            throw new IllegalArgumentException("文件所属数据集不存在或已删除: " + fileMeta.getDataSetId());
        }
        if (!processingType.categoryId().equals(dataSet.getCategoryId())) {
            throw new IllegalArgumentException("处理类型与数据集类别不匹配: "
                    + processingType.name() + ", categoryId=" + dataSet.getCategoryId());
        }
        return fileMeta;
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

    private void validateBatchRequest(GisCreateProcessingTaskRequest request,
            List<MultipartFile> files) {
        if (request == null || request.processingType() == null) {
            throw new IllegalArgumentException("处理类型不能为空");
        }
        if (request.taskName() == null || request.taskName().isBlank()
                || request.taskName().length() > 200) {
            throw new IllegalArgumentException("任务名称不能为空且不能超过200字");
        }
        if (request.parameters() == null) {
            throw new IllegalArgumentException("处理参数不能为空");
        }
        FileUploadUtil.validateBatch(files, MAX_FILES, MAX_TOTAL_SIZE,
                "文件数量必须在1到100之间", "处理文件不能为空",
                "单次处理文件总大小不能超过5GB");
    }

    private void validateParameters(GisProcessingType type, GisProcessingParameters parameters) {
        if (parameters.targetCrs() == null || parameters.tileProfile() == null
                || parameters.outputFormat() == null) {
            throw new IllegalArgumentException("目标坐标系、瓦片剖面和输出格式不能为空");
        }
        if (type == GisProcessingType.TERRAIN &&
                (!"EPSG:4326".equalsIgnoreCase(parameters.targetCrs())
                || !"GEODETIC".equalsIgnoreCase(parameters.tileProfile())
                || !"QUANTIZED_MESH".equalsIgnoreCase(parameters.outputFormat()))) {
            throw new IllegalArgumentException(
                    "当前地形引擎仅支持 EPSG:4326、GEODETIC、QUANTIZED_MESH");
        }
    }

    static String displayName(Path folder) {
        Path fileName = folder.getFileName();
        return fileName == null ? folder.toString() : fileName.toString();
    }

    private void deleteStoredQuietly(String key) {
        try { fileUploadUtil.deleteStored(key); } catch (Exception ignored) { }
    }

    private void deleteStagedQuietly(String key) {
        try { fileUploadUtil.deleteStaged(key); } catch (Exception ignored) { }
    }
}
