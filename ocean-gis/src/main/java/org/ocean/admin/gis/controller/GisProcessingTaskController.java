package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateProcessingTaskRequest;
import org.ocean.admin.gis.dto.GisManagedProcessingRequest;
import org.ocean.admin.gis.dto.GisWorkspaceProcessingRequest;
import org.ocean.admin.gis.service.GisProcessingTaskRecordService;
import org.ocean.admin.gis.service.ProcessingService;
import org.ocean.admin.gis.vo.GisBatchProcessingTaskVO;
import org.ocean.admin.gis.vo.GisProcessingTaskDetailVO;
import org.ocean.admin.gis.vo.GisProcessingTaskSummaryVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/** GIS 静态瓦片处理任务接口。 */
@Tag(name = "GIS处理任务管理", description = "地形、影像和矢量静态瓦片处理任务接口")
@RestController
@RequestMapping("/api/gis/task")
@RequiredArgsConstructor
public class GisProcessingTaskController {

    private final GisProcessingTaskRecordService taskService;
    private final ProcessingService processingService;

    @GetMapping("/page")
    @Operation(summary = "分页条件查询 GIS 处理任务")
    @OperationLog(module = "GIS_PROCESSING_TASK", type = OperationType.QUERY,
            description = "分页条件查询 GIS 处理任务", recordResponse = true)
    public ResponseResult<PageResult<List<GisProcessingTaskSummaryVO>>> getTaskPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String taskNo,
            @RequestParam(required = false) String taskName,
            @Parameter(description = "处理类型：TERRAIN、IMAGERY、VECTOR")
            @RequestParam(required = false) String processingType,
            @Parameter(description = "输入来源：UPLOAD、WORKSPACE、MANAGED_FILE")
            @RequestParam(required = false) String sourceType,
            @Parameter(description = "任务状态：QUEUED、RUNNING、COMPLETED、PARTIAL_FAILED、FAILED")
            @RequestParam(required = false) String taskStatus,
            @RequestParam(required = false) Long dataSetId,
            @Parameter(description = "创建开始时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createTimeStart,
            @Parameter(description = "创建结束时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createTimeEnd) {
        return ResponseResult.success(taskService.getTaskPage(current, size, taskNo, taskName,
                processingType, sourceType, taskStatus, dataSetId,
                createTimeStart, createTimeEnd));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查询 GIS 处理任务详情",
            description = "返回任务状态、处理参数快照、完整输入列表和唯一静态瓦片集")
    @OperationLog(module = "GIS_PROCESSING_TASK", type = OperationType.QUERY,
            description = "查询 GIS 处理任务详情", recordResponse = true)
    public ResponseResult<GisProcessingTaskDetailVO> getTaskDetail(
            @PathVariable Long taskId) {
        return ResponseResult.success(taskService.getTaskDetail(taskId));
    }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "新建多文件处理任务",
            description = "request 为 JSON，files 为本次上传的文件；地形支持多文件合并，首期影像任务仅允许一个 GeoTIFF")
    @OperationLog(module = "GIS_PROCESSING_TASK", type = OperationType.SUBMIT,
            description = "新建GIS多文件处理任务", recordResponse = true)
    public ResponseResult<GisBatchProcessingTaskVO> createProcessingTask(
            @RequestBody(content = @Content(encoding = @Encoding(
                    name = "request", contentType = MediaType.APPLICATION_JSON_VALUE)))
            @Valid @RequestPart("request") GisCreateProcessingTaskRequest request,
            @Parameter(description = "待处理文件列表", required = true)
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseResult.success("处理任务已提交",
                processingService.submitBatch(request, files));
    }

    @PostMapping("/process-workspace")
    @Operation(summary = "提交受控工作空间切片任务",
            description = "仅接受已配置工作空间编码及其根目录下的相对文件或目录路径")
    @OperationLog(module = "GIS_PROCESSING_TASK", type = OperationType.SUBMIT,
            description = "提交GIS受控工作空间切片任务", recordResponse = true)
    public ResponseResult<GisBatchProcessingTaskVO> processWorkspace(
            @Valid @org.springframework.web.bind.annotation.RequestBody
            GisWorkspaceProcessingRequest request) {
        return ResponseResult.success("工作空间处理任务已提交",
                processingService.submitWorkspace(request));
    }

    @PostMapping("/process-managed")
    @Operation(summary = "提交已管理文件切片任务",
            description = "使用文件元数据管理中的一个或多个 READY 文件生成一个静态瓦片集")
    @OperationLog(module = "GIS_PROCESSING_TASK", type = OperationType.SUBMIT,
            description = "提交GIS已管理文件切片任务", recordResponse = true)
    public ResponseResult<GisBatchProcessingTaskVO> processManaged(
            @Valid @org.springframework.web.bind.annotation.RequestBody
            GisManagedProcessingRequest request) {
        return ResponseResult.success("已管理文件处理任务已提交",
                processingService.submitManaged(request));
    }
}
