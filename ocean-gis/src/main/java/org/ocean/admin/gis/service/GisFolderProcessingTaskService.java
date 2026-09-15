package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.processing.GisFolderProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;

/** 在事务中创建服务器目录切片任务。 */
@Service
@RequiredArgsConstructor
public class GisFolderProcessingTaskService {

    private final GisTaskService gisTaskService;

    @Transactional(rollbackFor = Exception.class)
    public GisFolderProcessingExecution create(
            String taskNo, Path inputFolder, GisProcessingWorkspace workspace) {
        LocalDateTime now = LocalDateTime.now();
        GisTask task = new GisTask();
        task.setTaskNo(taskNo);
        task.setTaskName("地形目录处理：" + GisFolderProcessingService.displayName(inputFolder));
        task.setTaskType(2L);
        task.setPriority(0);
        task.setTotalCount(1L);
        task.setCompletedCount(0L);
        task.setFailedCount(0L);
        task.setTaskStatus("QUEUED");
        task.setCurrentStage("QUEUED");
        task.setProcessingType(GisProcessingType.TERRAIN.name());
        task.setOutputKey(workspace.outputKey());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        gisTaskService.insert(task);
        return new GisFolderProcessingExecution(task.getId(), taskNo, inputFolder, workspace);
    }
}
