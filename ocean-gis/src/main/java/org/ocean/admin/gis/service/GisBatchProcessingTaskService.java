package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.GisStagedFile;
import org.ocean.admin.gis.dto.GisStoredFile;
import org.ocean.admin.gis.entity.GisProcessingTaskFile;
import org.ocean.admin.gis.entity.GisTask;
import org.ocean.admin.gis.mapper.GisProcessingTaskFileMapper;
import org.ocean.admin.gis.processing.GisBatchProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 一个事务中创建多文件任务及其文件工作项。 */
@Service
@RequiredArgsConstructor
public class GisBatchProcessingTaskService {
    private final GisTaskService gisTaskService;
    private final GisProcessingTaskFileMapper fileMapper;

    @Transactional(rollbackFor = Exception.class)
    public GisBatchProcessingExecution create(String taskNo,
            GisCreateProcessingTaskRequest request,
            GisProcessingWorkspace workspace,
            List<GisStagedFile> stagedFiles,
            List<GisStoredFile> storedFiles) {
        LocalDateTime now = LocalDateTime.now();
        GisProcessingParameters parameters = request.parameters();
        GisTask task = new GisTask();
        task.setTaskNo(taskNo);
        task.setTaskName(request.taskName().trim());
        task.setTaskType(2L);
        task.setPriority(0);
        task.setTotalCount((long) storedFiles.size());
        task.setCompletedCount(0L);
        task.setFailedCount(0L);
        task.setTaskStatus("QUEUED");
        task.setCurrentStage("QUEUED");
        task.setProcessingType(request.processingType().name());
        task.setTargetCrs(parameters.targetCrs());
        task.setTileProfile(parameters.tileProfile());
        task.setOutputFormat(parameters.outputFormat());
        task.setOutputKey(workspace.outputKey());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setDeleted(0);
        gisTaskService.insert(task);

        List<GisProcessingTaskFile> items = new ArrayList<>(storedFiles.size());
        for (int i = 0; i < storedFiles.size(); i++) {
            GisProcessingTaskFile item = new GisProcessingTaskFile();
            item.setTaskId(task.getId());
            item.setFileIndex(i + 1);
            item.setOriginalName(stagedFiles.get(i).getOriginalName());
            item.setStorageKey(storedFiles.get(i).getStorageKey());
            item.setOutputKey(workspace.outputKey() + "/tiles/" + String.format("%04d", i + 1));
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
