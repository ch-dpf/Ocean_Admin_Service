package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisManagedProcessingRequest;
import org.ocean.admin.gis.dto.ImageryProcessingParameters;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.TerrainProcessingParameters;
import org.ocean.admin.gis.dto.GisWorkspaceProcessingRequest;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.imagery.ImageryTileOptions;
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
import org.ocean.admin.gis.terrain.engine.TerrainOptions;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 三种输入来源共用的 GIS 切片任务编排。 */
@Service
@RequiredArgsConstructor
public class ProcessingService {
    private static final int MAX_FILES = 100;
    private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024;
    private static final String DEFAULT_WORKSPACE_CODE = "default";
    private static final String DEFAULT_WORKSPACE_RELATIVE_PATH = ".";

    private final GisProcessingStorageService storageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisProcessingInputResolverRegistry inputResolverRegistry;
    private final GisProcessingTaskService transactionService;
    private final ProcessingWorker worker;
    private final GisProcessingTaskLifecycleService taskLifecycle;
    private final ObjectMapper objectMapper;

    public GisBatchProcessingTaskVO submitBatch(GisCreateProcessingTaskRequest request,
            List<MultipartFile> files) {
        validateUploadRequest(request, files);
        return submit(new GisSubmitProcessingCommand(GisInputSourceType.UPLOAD,
                request.processingType(), request.taskName(),
                readParameters(request.processingType(), request.parameters()), files,
                null, null, null));
    }

    public GisBatchProcessingTaskVO submitWorkspace(GisWorkspaceProcessingRequest request) {
        return submit(new GisSubmitProcessingCommand(GisInputSourceType.WORKSPACE,
                request.processingType(), request.taskName(),
                readParameters(request.processingType(), request.parameters()), null,
                defaultIfBlank(request.workspaceCode(), DEFAULT_WORKSPACE_CODE),
                defaultIfBlank(request.relativePath(), DEFAULT_WORKSPACE_RELATIVE_PATH), null));
    }

    public GisBatchProcessingTaskVO submitManaged(GisManagedProcessingRequest request) {
        return submit(new GisSubmitProcessingCommand(GisInputSourceType.MANAGED_FILE,
                request.processingType(), request.taskName(),
                readParameters(request.processingType(), request.parameters()), null,
                null, null, request.fileMetaIds()));
    }

    private GisProcessingParameters readParameters(
            GisProcessingType type, JsonNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return null;
        }
        if (type == null) {
            throw new IllegalArgumentException("处理类型不能为空");
        }
        try {
            return switch (type) {
                case TERRAIN -> objectMapper.treeToValue(
                        normalizeTerrainZoomFields(parameters),
                        TerrainProcessingParameters.class);
                case IMAGERY -> objectMapper.treeToValue(
                        parameters, ImageryProcessingParameters.class);
                case VECTOR -> objectMapper.treeToValue(parameters,
                        GisProcessingParameters.VectorProcessingParameters.class);
            };
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(type.displayName() + "参数格式不正确", ex);
        }
    }

    private JsonNode normalizeTerrainZoomFields(JsonNode parameters) {
        if (!(parameters instanceof ObjectNode objectParameters)) {
            return parameters;
        }
        ObjectNode normalized = objectParameters.deepCopy();
        moveLegacyField(normalized, "minDepth", "minZoom");
        moveLegacyField(normalized, "maxDepth", "maxZoom");
        return normalized;
    }

    private void moveLegacyField(ObjectNode parameters, String legacyName, String currentName) {
        if (!parameters.has(currentName) && parameters.has(legacyName)) {
            parameters.set(currentName, parameters.get(legacyName));
        }
        parameters.remove(legacyName);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private GisBatchProcessingTaskVO submit(GisSubmitProcessingCommand command) {
        validateCommand(command);
        GisFileProcessingEngine engine = engineRegistry.require(command.processingType());
        String taskNo = GisProcessingTaskFactory.generateTaskNo(command.processingType());
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

    private void validateParameters(GisProcessingType type, GisProcessingParameters parameters) {
        if (parameters.processingType() != type) {
            throw new IllegalArgumentException(
                    "处理类型与参数类型不一致: " + type + "/" + parameters.processingType());
        }
        if (parameters instanceof TerrainProcessingParameters terrainParameters) {
            TerrainOptions.from(terrainParameters);
            return;
        }
        if (parameters instanceof ImageryProcessingParameters imageryParameters) {
            ImageryTileOptions.from(imageryParameters);
        }
    }
}
