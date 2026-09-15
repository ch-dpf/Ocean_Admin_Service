package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.ocean.admin.kernel.task.TaskProgressService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GisTaskLifecycleServiceTest {
    @Test
    void marksPersistedTaskFailedWhenDispatchFails() {
        GisTaskService taskService = mock(GisTaskService.class);
        TaskProgressService progressService = mock(TaskProgressService.class);
        GisTaskLifecycleService lifecycle = new GisTaskLifecycleService(taskService, progressService);
        Runnable workerDispatch = () -> {
            throw new IllegalStateException("executor rejected");
        };

        assertThrows(IllegalStateException.class,
                () -> lifecycle.dispatch(1L, "TASK", "任务", 1, "GIS_TERRAIN",
                        workerDispatch, "切片任务启动失败: "));

        verify(progressService).registerTask("TASK", "任务", 1, "GIS_TERRAIN");
        verify(taskService).markFailed(1L, "executor rejected");
        verify(progressService).finalizeTaskFailure("TASK", "切片任务启动失败: executor rejected");
        verify(taskService, never()).markRunning(1L);
    }
}
