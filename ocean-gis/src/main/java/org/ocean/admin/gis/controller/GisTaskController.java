package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.service.ProcessingService;
import org.ocean.admin.gis.vo.GisProcessingTaskFileVO;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.ocean.admin.gis.service.GisTaskService;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.ocean.admin.gis.vo.GisTaskVO;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.Valid;

/**
 * GIS任务管理
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Slf4j
@Tag(name = "GIS任务管理", description = "GIS任务管理接口")
@RestController
@RequestMapping("/api/gis/task")
@RequiredArgsConstructor
public class GisTaskController {

    private final GisTaskService gisTaskService;
    private final ProcessingService processingService;

    @GetMapping("/page")
    @Operation(summary = "分页条件查询 GIS 任务")
    @OperationLog(module = "GIS_TASK", type = OperationType.QUERY, description = "分页条件查询 GIS 任务",
            recordResponse = true)
    public ResponseResult<PageResult<List<GisTaskVO>>> getTaskPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String taskNo,
            @RequestParam(required = false) String taskName,
            @Parameter(description = "任务类型")
            @RequestParam(required = false) Long taskType,
            @Parameter(description = "任务状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED")
            @RequestParam(required = false) String taskStatus,
            @RequestParam(required = false) Long dataSetId,
            @Parameter(description = "创建开始时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createTimeStart,
            @Parameter(description = "创建结束时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createTimeEnd) {
        return ResponseResult.success(gisTaskService.getTaskPage(
                current,
                size,
                taskNo,
                taskName,
                taskType,
                taskStatus,
                dataSetId,
                createTimeStart,
                createTimeEnd));
    }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "新建多文件处理任务",
            description = "request 为 JSON，files 为本次上传的文件；多个输入文件合并生成一个瓦片集")
    @OperationLog(module = "GIS_TASK", type = OperationType.SUBMIT,
            description = "新建GIS多文件处理任务", recordResponse = true)
    public ResponseResult<GisBatchProcessingTaskVO> createProcessingTask(
            @Valid @RequestPart("request") GisCreateProcessingTaskRequest request,
            @Parameter(description = "待处理文件列表", required = true)
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseResult.success("处理任务已提交",
                processingService.submitBatch(request, files));
    }

    @GetMapping("/{taskId}/processing-files")
    @Operation(summary = "查询多文件处理任务的逐文件结果")
    public ResponseResult<List<GisProcessingTaskFileVO>> getProcessingFiles(
            @PathVariable Long taskId) {
        return ResponseResult.success(processingService.getBatchFiles(taskId));
    }

    @PostMapping("/process-folder")
    @Operation(summary = "提交服务器文件夹切片任务",
            description = "将服务器任意目录作为一个整体输入，异步处理目录下的所有地形文件")
    @OperationLog(module = "GIS_TASK", type = OperationType.SUBMIT,
            description = "提交GIS服务器文件夹切片任务", recordResponse = true)
    public ResponseResult<GisProcessingTaskVO> processFolder(
            @Parameter(description = "服务器文件夹路径", required = true)
            @RequestParam("folderPath") String folderPath) {
        return ResponseResult.success("文件夹处理任务已提交",
                processingService.submitFolder(folderPath));
    }
}
