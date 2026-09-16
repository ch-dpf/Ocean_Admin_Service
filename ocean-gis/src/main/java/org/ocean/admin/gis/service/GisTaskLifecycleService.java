package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisTask;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/** 协调 GIS 持久化任务与实时进度的状态流转。 */
@Service
@RequiredArgsConstructor
public class GisTaskLifecycleService {
    private final GisTaskService taskService;
    private final AsyncTaskService progressService;

    public void dispatch(Long taskId, String taskNo, String taskName, int totalCount,
            String progressType, Runnable workerDispatch, String failureMessage) {
        try {
            progressService.registerTask(taskNo, taskName, totalCount, progressType);
            workerDispatch.run();
        } catch (Exception ex) {
            fail(taskId, taskNo, failureMessage, ex);
            throw ex;
        }
    }

    public void start(Long taskId, String taskNo, int percent, String message) {
        taskService.markRunning(taskId);
        progressService.updateProgress(taskNo, percent, "processing", message);
    }

    public void recordResult(Long taskId, String taskNo, boolean success) {
        if (success) {
            taskService.incrementCompleted(taskId);
        } else {
            taskService.incrementFailed(taskId);
        }
        progressService.updateProgress(taskNo, success);
    }

    public GisTask finish(Long taskId, String taskNo, Function<GisTask, String> resultMessage) {
        GisTask finished = taskService.finish(taskId);
        progressService.finalizeTaskResult(taskNo, resultMessage.apply(finished));
        return finished;
    }

    public void fail(Long taskId, String taskNo, String message, Exception ex) {
        taskService.markFailed(taskId, ex.getMessage());
        progressService.finalizeTaskFailure(taskNo, message + ex.getMessage());
    }
}
