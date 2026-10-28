package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.progress.ProgressSnapshot.Handle;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressSummary;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.progress.ProgressUpdate;
import org.ocean.admin.gis.progress.RealtimeProgressService;
import org.springframework.stereotype.Service;
import java.util.function.Function;

/** 数据库负责业务结果，进度中心投影已提交的状态。 */
@Service
@RequiredArgsConstructor
public class ProcessingTaskLifecycleService {
    private final ProcessingTaskRecordService taskService;
    private final RealtimeProgressService progressService;

    public void dispatch(Long taskId, String taskNo, String taskName, int totalCount,
            String progressType, Runnable workerDispatch, String failureMessage) {
        Handle handle = null;
        try {
            handle = progressService.create(taskNo, null, taskName, totalCount, progressType, ProgressUnit.INPUT);
            workerDispatch.run();
        } catch (Exception ex) {
            fail(taskId, handle, failureMessage, ex);
            throw ex;
        }
    }

    public Handle handle(String taskNo) { return progressService.handle(taskNo); }

    public void start(Long taskId, Handle handle, String message) {
        taskService.markRunning(taskId);
        progressService.update(handle, ProgressUpdate.stageChanged(ProgressStage.indeterminate("PROCESSING", message)));
    }

    public void reportProgress(Handle handle, ProgressStage stage) {
        progressService.update(handle, ProgressUpdate.stageChanged(stage));
    }

    public void recordResult(Long taskId, Handle handle, boolean success) {
        if (success) taskService.incrementCompleted(taskId);
        else taskService.incrementFailed(taskId);
        GisProcessingTask task = taskService.getRequired(taskId);
        progressService.update(handle, ProgressUpdate.resultChanged(task.getCompletedCount(), task.getFailedCount()));
    }

    public GisProcessingTask finish(Long taskId, Handle handle,
            Function<GisProcessingTask, String> resultMessage) {
        GisProcessingTask finished = taskService.finish(taskId);
        progressService.finish(handle, finished.getTaskStatus(), resultMessage.apply(finished));
        return finished;
    }

    public void fail(Long taskId, Handle handle, String message, Exception ex) {
        taskService.markFailed(taskId, ex.getMessage());
        if (handle == null) return;
        GisProcessingTask task = taskService.getRequired(taskId);
        progressService.update(handle, ProgressUpdate.summaryChanged(new ProgressSummary(task.getTotalCount(),
                task.getCompletedCount(), task.getFailedCount(), ProgressUnit.INPUT)));
        progressService.finish(handle, task.getTaskStatus(), message + ex.getMessage());
    }
}
