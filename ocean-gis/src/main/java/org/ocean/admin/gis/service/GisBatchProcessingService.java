package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.ocean.admin.gis.vo.GisProcessingTaskFileVO;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 本次上传文件的多文件切片任务编排。 */
@Service
@RequiredArgsConstructor
public class GisBatchProcessingService {
    private static final int MAX_FILES = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;

    private final FileStorageService fileStorageService;
    private final GisProcessingStorageService processingStorageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisBatchProcessingTaskService transactionService;
    private final GisBatchProcessingWorker worker;
    private final GisTaskService gisTaskService;
    private final TaskProgressService taskProgressService;
    private final GisProcessingTaskFileMapper fileMapper;

    public List<GisProcessingTaskFileVO> getFiles(Long taskId) {
        gisTaskService.getRequired(taskId);
        return fileMapper.selectByTaskId(taskId).stream()
                .map(file -> new GisProcessingTaskFileVO(file.getId(), file.getFileIndex(),
                        file.getOriginalName(), file.getOutputKey(), file.getStatus(),
                        file.getErrorMessage()))
                .toList();
    }

    public GisBatchProcessingTaskVO submit(GisCreateProcessingTaskRequest request,
            List<MultipartFile> files) {
        validateRequest(request, files);
        GisFileProcessingEngine engine = engineRegistry.require(request.processingType());
        validateParameters(request.processingType(), request.parameters());
        String taskNo = "GIS_" + request.processingType().name() + "_BATCH_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        GisProcessingWorkspace workspace = processingStorageService.batchWorkspace(
                request.processingType(), taskNo);
        List<GisStagedFile> staged = new ArrayList<>(files.size());
        List<GisStoredFile> stored = new ArrayList<>(files.size());
        GisBatchProcessingExecution execution = null;
        try {
            for (MultipartFile file : files) {
                GisStagedFile stagedFile = fileStorageService.stage(taskNo, file);
                staged.add(stagedFile);
                GisFileMeta meta = new GisFileMeta();
                meta.setExtension(stagedFile.getExtension());
                meta.setOriginalName(stagedFile.getOriginalName());
                engine.validate(meta, fileStorageService.resolveStoredPath(stagedFile.getStagingKey()));
            }
            for (GisStagedFile stagedFile : staged) {
                stored.add(fileStorageService.commitForProcessing(taskNo, stagedFile));
            }
            execution = transactionService.create(taskNo, request, workspace, staged, stored);
            taskProgressService.registerTask(taskNo, request.taskName().trim(),
                    stored.size(), "GIS_" + request.processingType().name());
            worker.process(execution);
            return GisBatchProcessingTaskVO.builder()
                    .taskId(execution.taskId())
                    .taskNo(taskNo)
                    .processingType(request.processingType().name())
                    .outputKey(workspace.outputKey())
                    .totalCount(stored.size())
                    .status("QUEUED")
                    .build();
        } catch (Exception ex) {
            if (execution != null) {
                gisTaskService.markFailed(execution.taskId(), ex.getMessage());
                taskProgressService.finalizeTaskFailure(taskNo, "处理任务启动失败: " + ex.getMessage());
            }
            stored.forEach(file -> deleteStoredQuietly(file.getStorageKey()));
            staged.forEach(file -> deleteStagedQuietly(file.getStagingKey()));
            throw ex;
        }
    }

    private void validateRequest(GisCreateProcessingTaskRequest request, List<MultipartFile> files) {
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
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) {
            throw new IllegalArgumentException("文件数量必须在1到100之间");
        }
        long total = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("处理文件不能为空");
            }
            total = Math.addExact(total, file.getSize());
        }
        if (total > MAX_TOTAL_SIZE) {
            throw new IllegalArgumentException("单次处理文件总大小不能超过5GB");
        }
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

    private void deleteStoredQuietly(String key) {
        try { fileStorageService.deleteStored(key); } catch (Exception ignored) { }
    }

    private void deleteStagedQuietly(String key) {
        try { fileStorageService.deleteStaged(key); } catch (Exception ignored) { }
    }
}
