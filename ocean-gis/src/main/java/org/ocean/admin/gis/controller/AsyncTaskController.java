package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.service.AsyncTaskService;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 异步任务管理Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/task")
@Tag(name = "异步任务管理", description = "查看和管理后台异步任务")
@RequiredArgsConstructor
public class AsyncTaskController {

    private final AsyncTaskService asyncTaskService;

    /**
     * 获取所有运行中的任务
     */
    @GetMapping("/running")
    @Operation(summary = "获取运行中的任务", description = "获取当前正在执行的异步任务列表")
    public ResponseResult<List<AsyncTaskService.TaskInfo>> getRunningTasks() {
        try {
            List<AsyncTaskService.TaskInfo> tasks = asyncTaskService.getRunningTasks();
            return ResponseResult.success(tasks);
        } catch (Exception e) {
            log.error("获取运行中任务失败: {}", e.getMessage(), e);
            return ResponseResult.error("获取任务列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有任务（包括已完成）
     */
    @GetMapping("/all")
    @Operation(summary = "获取所有任务", description = "获取所有异步任务列表，包括已完成的")
    public ResponseResult<List<AsyncTaskService.TaskInfo>> getAllTasks() {
        try {
            List<AsyncTaskService.TaskInfo> tasks = asyncTaskService.getAllTasks();
            return ResponseResult.success(tasks);
        } catch (Exception e) {
            log.error("获取所有任务失败: {}", e.getMessage(), e);
            return ResponseResult.error("获取任务列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取任务详情", description = "根据任务ID获取详细信息")
    public ResponseResult<AsyncTaskService.TaskInfo> getTaskInfo(@PathVariable String taskId) {
        try {
            AsyncTaskService.TaskInfo taskInfo = asyncTaskService.getTaskInfo(taskId);
            if (taskInfo == null) {
                return ResponseResult.error("任务不存在");
            }
            return ResponseResult.success(taskInfo);
        } catch (Exception e) {
            log.error("获取任务详情失败: {}", e.getMessage(), e);
            return ResponseResult.error("获取任务详情失败: " + e.getMessage());
        }
    }

    /**
     * 清理已完成的任务
     */
    @PostMapping("/cleanup")
    @Operation(summary = "清理已完成任务", description = "清理1小时前已完成的任务记录")
    public ResponseResult<String> cleanupCompletedTasks() {
        try {
            asyncTaskService.cleanupCompletedTasks();
            return ResponseResult.success("清理完成");
        } catch (Exception e) {
            log.error("清理任务失败: {}", e.getMessage(), e);
            return ResponseResult.error("清理失败: " + e.getMessage());
        }
    }
}
