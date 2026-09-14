package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.service.FileUploadService;
import org.ocean.admin.gis.service.GisTaskService;
import org.ocean.admin.gis.vo.GisTaskVO;
import org.ocean.admin.gis.vo.GisUploadTaskVO;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

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

    private final FileUploadService fileUploadService;
    private final GisTaskService gisTaskService;

    @GetMapping("/page")
    @Operation(summary = "分页条件查询 GIS 任务")
    public ResponseResult<PageResult<List<GisTaskVO>>> getTaskPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String taskNo,
            @RequestParam(required = false) String taskName,
            @Parameter(description = "任务类型：1-上传、2-切片、3-发布、4-导出或下载")
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

    @PostMapping(value = "/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "多文件上传", description = "一次上传多个Gis文件")
    public ResponseResult<GisUploadTaskVO> upload(
            @Parameter(description = "数据集ID") @RequestParam Long dataSetId,
            @Parameter(description = "文件列表", required = true)  @RequestPart("files") List<MultipartFile> files){
        return ResponseResult.success(
                fileUploadService.createUploadTask(dataSetId, files));
    }
}
