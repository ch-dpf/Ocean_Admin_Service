package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisTaskMapper;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;

/** 在事务中创建单文件切片任务。 */
@Service
@RequiredArgsConstructor
public class GisFileProcessingTaskService {

    private final GisTaskMapper gisTaskMapper;
    private final GisTaskService gisTaskService;

    @Transactional(rollbackFor = Exception.class)
    public GisProcessingExecution create(
            String taskNo,
            GisFileMeta fileMeta,
            GisProcessingType processingType,
            Path inputPath,
            GisProcessingWorkspace workspace) {
        boolean active = gisTaskMapper.exists(new LambdaQueryWrapper<GisTask>()
                .eq(GisTask::getSourceFileMetaId, fileMeta.getId())
                .eq(GisTask::getProcessingType, processingType.name())
                .in(GisTask::getTaskStatus, "QUEUED", "RUNNING"));
        if (active) {
            throw new IllegalStateException("该文件已有同类型切片任务正在执行");
        }

        LocalDateTime now = LocalDateTime.now();
        GisTask task = new GisTask();
        task.setTaskNo(taskNo);
        task.setTaskName(processingType.displayName() + "：" + fileMeta.getOriginalName());
        task.setTaskType(2L);
        task.setPriority(0);
        task.setTotalCount(1L);
        task.setCompletedCount(0L);
        task.setFailedCount(0L);
        task.setTaskStatus("QUEUED");
        task.setCurrentStage("QUEUED");
        task.setDataSetId(fileMeta.getDataSetId());
        task.setProcessingType(processingType.name());
        task.setSourceFileMetaId(fileMeta.getId());
        task.setOutputKey(workspace.outputKey());
        task.setParentTaskId(fileMeta.getTaskId());
        task.setRootTaskId(fileMeta.getTaskId());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        gisTaskService.insert(task);

        return new GisProcessingExecution(
                task.getId(), taskNo, fileMeta.getId(), processingType, inputPath, workspace);
    }
}
