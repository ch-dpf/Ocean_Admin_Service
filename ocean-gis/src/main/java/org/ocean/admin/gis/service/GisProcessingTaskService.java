package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.TempFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisProcessingTaskFile;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.mapper.GisTaskMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 在独立事务中创建单文件、目录和多文件切片任务。 */
@Service
@RequiredArgsConstructor
public class GisProcessingTaskService {
    private final GisTaskService gisTaskService;
    private final GisTaskMapper gisTaskMapper;
    private final GisProcessingTaskFileMapper fileMapper;

    @Transactional(rollbackFor = Exception.class)
    public GisProcessingExecution createSingle(String taskNo, GisFileMeta fileMeta,
            GisProcessingType processingType, Path inputPath, GisProcessingWorkspace workspace) {
        boolean active = gisTaskMapper.exists(new LambdaQueryWrapper<GisTask>()
                .eq(GisTask::getSourceFileMetaId, fileMeta.getId())
                .eq(GisTask::getProcessingType, processingType.name())
                .in(GisTask::getTaskStatus, "QUEUED", "RUNNING"));
        if (active) {
            throw new IllegalStateException("该文件已有同类型切片任务正在执行");
        }

        GisTask task = GisTaskFactory.queued(taskNo,
                processingType.displayName() + "：" + fileMeta.getOriginalName(),
                2L, 1L, "QUEUED");
        task.setDataSetId(fileMeta.getDataSetId());
        task.setProcessingType(processingType.name());
        task.setSourceFileMetaId(fileMeta.getId());
        task.setOutputKey(workspace.outputKey());
        gisTaskService.insert(task);

        return new GisProcessingExecution(
                task.getId(), taskNo, fileMeta.getId(), processingType, inputPath, workspace);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisFolderProcessingExecution createFolder(
            String taskNo, Path inputFolder, GisProcessingWorkspace workspace) {
        GisTask task = GisTaskFactory.queued(taskNo,
                "地形目录处理：" + ProcessingService.displayName(inputFolder),
                2L, 1L, "QUEUED");
        task.setProcessingType(GisProcessingType.TERRAIN.name());
        task.setOutputKey(workspace.outputKey());
        gisTaskService.insert(task);
        return new GisFolderProcessingExecution(task.getId(), taskNo, inputFolder, workspace);
    }

    @Transactional(rollbackFor = Exception.class)
    public GisBatchProcessingExecution createBatch(String taskNo,
                                                   GisCreateProcessingTaskRequest request, GisProcessingWorkspace workspace,
                                                   List<TempFile> stagedFiles, List<GisStoredFile> storedFiles) {
        GisProcessingParameters parameters = request.parameters();
        GisTask task = GisTaskFactory.queued(taskNo, request.taskName().trim(),
                2L, storedFiles.size(), "QUEUED");
        task.setProcessingType(request.processingType().name());
        task.setTargetCrs(parameters.targetCrs());
        task.setTileProfile(parameters.tileProfile());
        task.setOutputFormat(parameters.outputFormat());
        task.setOutputKey(workspace.outputKey());
        gisTaskService.insert(task);

        List<GisProcessingTaskFile> items = new ArrayList<>(storedFiles.size());
        for (int i = 0; i < storedFiles.size(); i++) {
            GisProcessingTaskFile item = new GisProcessingTaskFile();
            item.setTaskId(task.getId());
            item.setFileIndex(i + 1);
            item.setOriginalName(stagedFiles.get(i).getOriginalName());
            item.setStorageKey(storedFiles.get(i).getStorageKey());
            item.setOutputKey(workspace.outputKey());
            item.setStatus("QUEUED");
            if (fileMapper.insert(item) != 1) {
                throw new IllegalStateException("处理文件工作项创建失败");
            }
            items.add(item);
        }
        return new GisBatchProcessingExecution(task.getId(), taskNo,
                request.processingType(), workspace, List.copyOf(items));
    }
}
