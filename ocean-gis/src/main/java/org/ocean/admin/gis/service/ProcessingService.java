package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisFileProcessRequest;
import org.ocean.admin.gis.dto.GisManagedProcessingRequest;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.GisWorkspaceProcessingRequest;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.processing.input.GisProcessingInputResolver;
import org.ocean.admin.gis.processing.input.GisProcessingInputResolverRegistry;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 三种输入来源共用的 GIS 切片任务编排。 */
@Service
@RequiredArgsConstructor
public class ProcessingService {
    private static final int MAX_FILES = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;

    private final GisProcessingStorageService storageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisProcessingInputResolverRegistry inputResolverRegistry;
    private final GisProcessingTaskService transactionService;
    private final ProcessingWorker worker;
    private final GisProcessingTaskLifecycleService taskLifecycle;

    public GisProcessingTaskVO submitSingle(Long fileMetaId, GisFileProcessRequest request) {
        if (request == null || request.getProcessingType() == null) {
            throw new IllegalArgumentException("处理类型不能为空");
        }
        return submitSingle(fileMetaId, request.getProcessingType(), request.getParameters());
    }

    public GisProcessingTaskVO submitSingleImagery(
            Long fileMetaId, GisProcessingParameters parameters) {
        return submitSingle(fileMetaId, GisProcessingType.IMAGERY, parameters);
    }

    private GisProcessingTaskVO submitSingle(Long fileMetaId, GisProcessingType processingType,
            GisProcessingParameters requestedParameters) {
        GisProcessingParameters parameters = parametersOrDefault(
                processingType, requestedParameters);
        GisSubmitProcessingCommand command = new GisSubmitProcessingCommand(
                GisInputSourceType.MANAGED_FILE, processingType,
                processingType.displayName() + "：" + fileMetaId,
                parameters, null, null, null, List.of(fileMetaId));
        GisBatchProcessingTaskVO submitted = submit(command);
        return GisProcessingTaskVO.builder()
                .taskId(submitted.getTaskId())
                .tileSetId(submitted.getTileSetId())
                .taskNo(submitted.getTaskNo())
                .fileMetaId(fileMetaId)
                .processingType(submitted.getProcessingType())
                .outputKey(submitted.getOutputKey())
                .status(submitted.getStatus())
                .build();
    }

    public GisBatchProcessingTaskVO submitBatch(GisCreateProcessingTaskRequest request,
            List<MultipartFile> files) {
        validateUploadRequest(request, files);
        return submit(new GisSubmitProcessingCommand(GisInputSourceType.UPLOAD,
                request.processingType(), request.taskName(), request.parameters(), files,
                null, null, null));
    }

    public GisBatchProcessingTaskVO submitWorkspace(GisWorkspaceProcessingRequest request) {
        return submit(new GisSubmitProcessingCommand(GisInputSourceType.WORKSPACE,
                request.processingType(), request.taskName(), request.parameters(), null,
                request.workspaceCode(), request.relativePath(), null));
    }

    public GisBatchProcessingTaskVO submitManaged(GisManagedProcessingRequest request) {
        return submit(new GisSubmitProcessingCommand(GisInputSourceType.MANAGED_FILE,
                request.processingType(), request.taskName(), request.parameters(), null,
                null, null, request.fileMetaIds()));
    }

    private GisBatchProcessingTaskVO submit(GisSubmitProcessingCommand command) {
        validateCommand(command);
        GisFileProcessingEngine engine = engineRegistry.require(command.processingType());
        String taskNo = GisProcessingTaskFactory.generateTaskNo(
                "GIS_" + command.processingType().name() + "_PROCESS");
        GisProcessingWorkspace workspace = storageService.taskWorkspace(
                command.processingType(), taskNo);
        GisProcessingInputResolver resolver = inputResolverRegistry.require(command.sourceType());
        GisProcessingInputResolver.PreparedInputs prepared = resolver.prepare(command, taskNo);
        try {
            validateResolvedInputs(command, engine, prepared.inputs(),
                    resolver.resolveRuntime(prepared.inputs()));
            GisProcessingTaskService.CreatedProcessingTask created = transactionService.create(
                    taskNo, command, workspace, prepared.inputs());
            taskLifecycle.dispatch(created.taskId(), taskNo, command.taskName().trim(),
                    prepared.inputs().size(), "GIS_" + command.processingType().name(),
                    () -> worker.process(created.taskId()), "处理任务启动失败: ");
            return GisBatchProcessingTaskVO.builder()
                    .taskId(created.taskId())
                    .tileSetId(created.tileSetId())
                    .taskNo(taskNo)
                    .sourceType(command.sourceType().name())
                    .processingType(command.processingType().name())
                    .outputKey(workspace.outputKey())
                    .totalCount(prepared.inputs().size())
                    .status("QUEUED")
                    .build();
        } catch (RuntimeException ex) {
            prepared.rollback().run();
            throw ex;
        }
    }

    private void validateResolvedInputs(GisSubmitProcessingCommand command,
            GisFileProcessingEngine engine, List<GisProcessingInput> inputs, List<Path> paths) {
        if (inputs.size() != paths.size()) {
            throw new IllegalStateException("处理输入快照与运行时资源数量不一致");
        }
        if (command.processingType() == GisProcessingType.IMAGERY && inputs.size() != 1) {
            throw new IllegalArgumentException("影像切片每个任务仅支持一个 GeoTIFF");
        }
        for (int i = 0; i < inputs.size(); i++) {
            GisProcessingInput input = inputs.get(i);
            Path path = paths.get(i);
            if (Files.isDirectory(path)) {
                if (command.processingType() != GisProcessingType.TERRAIN || inputs.size() != 1) {
                    throw new IllegalArgumentException("当前仅地形处理支持单个目录输入");
                }
                continue;
            }
            engine.validate(input.getOriginalName(), input.getExtension(), path);
        }
    }

    private void validateCommand(GisSubmitProcessingCommand command) {
        if (command == null || command.sourceType() == null || command.processingType() == null) {
            throw new IllegalArgumentException("输入来源和处理类型不能为空");
        }
        if (command.taskName() == null || command.taskName().isBlank()
                || command.taskName().length() > 200) {
            throw new IllegalArgumentException("任务名称不能为空且不能超过200字");
        }
        if (command.parameters() == null) {
            throw new IllegalArgumentException("处理参数不能为空");
        }
        validateParameters(command.processingType(), command.parameters());
    }

    private void validateUploadRequest(GisCreateProcessingTaskRequest request,
            List<MultipartFile> files) {
        if (request == null || request.processingType() == null) {
            throw new IllegalArgumentException("处理类型不能为空");
        }
        FileUploadUtil.validateBatch(files, MAX_FILES, MAX_TOTAL_SIZE,
                "文件数量必须在1到100之间", "处理文件不能为空",
                "单次处理文件总大小不能超过5GB");
    }

    private GisProcessingParameters parametersOrDefault(
            GisProcessingType type, GisProcessingParameters parameters) {
        if (parameters != null) {
            return parameters;
        }
        return switch (type) {
            case TERRAIN -> new GisProcessingParameters(
                    "EPSG:4326", "GEODETIC", "QUANTIZED_MESH",
                    null, null, null, null);
            case IMAGERY -> new GisProcessingParameters(
                    "EPSG:3857", "XYZ", "PNG", null, null, "BILINEAR", true);
            case VECTOR -> new GisProcessingParameters(
                    "EPSG:3857", "MVT", "PBF", null, null, null, null);
        };
    }

    private void validateParameters(GisProcessingType type, GisProcessingParameters parameters) {
        if (parameters.targetCrs() == null || parameters.tileProfile() == null
                || parameters.outputFormat() == null) {
            throw new IllegalArgumentException("目标坐标系、瓦片剖面和输出格式不能为空");
        }
        if (type == GisProcessingType.TERRAIN
                && (!"EPSG:4326".equalsIgnoreCase(parameters.targetCrs())
                || !"GEODETIC".equalsIgnoreCase(parameters.tileProfile())
                || !"QUANTIZED_MESH".equalsIgnoreCase(parameters.outputFormat()))) {
            throw new IllegalArgumentException(
                    "当前地形引擎仅支持 EPSG:4326、GEODETIC、QUANTIZED_MESH");
        }
        if (type == GisProcessingType.IMAGERY) {
            if (!"EPSG:3857".equalsIgnoreCase(parameters.targetCrs())
                    || !"XYZ".equalsIgnoreCase(parameters.tileProfile())
                    || !("PNG".equalsIgnoreCase(parameters.outputFormat())
                    || "JPEG".equalsIgnoreCase(parameters.outputFormat())
                    || "JPG".equalsIgnoreCase(parameters.outputFormat()))) {
                throw new IllegalArgumentException(
                        "当前影像引擎仅支持 EPSG:3857、XYZ、PNG/JPEG");
            }
            if (parameters.minZoom() != null && parameters.maxZoom() != null
                    && parameters.minZoom() > parameters.maxZoom()) {
                throw new IllegalArgumentException("影像最小层级不能大于最大层级");
            }
            if (parameters.resampling() != null
                    && !"NEAREST".equalsIgnoreCase(parameters.resampling())
                    && !"BILINEAR".equalsIgnoreCase(parameters.resampling())) {
                throw new IllegalArgumentException("影像重采样仅支持 NEAREST 或 BILINEAR");
            }
        }
    }
}
