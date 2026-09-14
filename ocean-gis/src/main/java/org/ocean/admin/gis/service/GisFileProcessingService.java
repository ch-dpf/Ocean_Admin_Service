package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisFileProcessRequest;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngineRegistry;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.ocean.admin.kernel.task.TaskProgressService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 已入库单文件切片的校验与异步任务编排。 */
@Service
@RequiredArgsConstructor
public class GisFileProcessingService {

    private final GisFileMetaService gisFileMetaService;
    private final GisDataSetMapper gisDataSetMapper;
    private final FileStorageService fileStorageService;
    private final GisProcessingStorageService processingStorageService;
    private final GisFileProcessingEngineRegistry engineRegistry;
    private final GisFileProcessingTaskService transactionService;
    private final GisFileProcessingWorker processingWorker;
    private final GisTaskService gisTaskService;
    private final TaskProgressService taskProgressService;

    public GisProcessingTaskVO submit(Long fileMetaId, GisFileProcessRequest request) {
        if (request == null || request.getProcessingType() == null) {
            throw new IllegalArgumentException("处理类型不能为空");
        }
        GisProcessingType processingType = request.getProcessingType();
        GisFileMeta fileMeta = validateFile(fileMetaId, processingType);
        Path inputPath = fileStorageService.resolveStoredPath(fileMeta.getStorageKey());
        GisFileProcessingEngine engine = engineRegistry.require(processingType);
        engine.validate(fileMeta, inputPath);

        String taskNo = generateTaskNo(processingType);
        GisProcessingWorkspace workspace = processingStorageService.workspace(
                processingType, fileMeta.getId(), taskNo);
        GisProcessingExecution execution = transactionService.create(
                taskNo, fileMeta, processingType, inputPath, workspace);
        try {
            taskProgressService.registerTask(
                    taskNo,
                    processingType.displayName() + "：" + fileMeta.getOriginalName(),
                    1,
                    "GIS_" + processingType.name());
            processingWorker.process(execution);
        } catch (Exception ex) {
            gisTaskService.markFailed(execution.taskId(), ex.getMessage());
            taskProgressService.finalizeTaskFailure(taskNo, "切片任务启动失败: " + ex.getMessage());
            throw ex;
        }

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

    private String generateTaskNo(GisProcessingType processingType) {
        return "GIS_" + processingType.name() + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
