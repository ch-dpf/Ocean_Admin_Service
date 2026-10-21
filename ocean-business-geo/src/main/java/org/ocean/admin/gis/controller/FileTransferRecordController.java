package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.FileTransferRecordService;
import org.ocean.admin.gis.service.FileUploadService;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.gis.vo.GisFileTransferRecordVO;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GIS 文件传输记录查询及导入重试入口。
 */
@RestController
@RequestMapping("/api/gis/file-transfer-records")
@Tag(name = "GIS文件传输记录", description = "原始文件批量上传、下载传输记录查询接口")
@RequiredArgsConstructor
public class FileTransferRecordController {

    private final FileTransferRecordService fileTransferRecordService;
    private final FileUploadService fileUploadService;

    @GetMapping
    @Operation(summary = "分页查询文件传输记录")
    public ResponseResult<PageResult<List<GisFileTransferRecordVO>>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String recordNo,
            @Parameter(description = "传输类型：IMPORT-上传导入，EXPORT-下载导出")
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String recordStatus,
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String uploadStatus,
            @Parameter(description = "创建时间起点，ISO-8601格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createTimeStart,
            @Parameter(description = "创建时间终点，ISO-8601格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createTimeEnd) {
        return ResponseResult.success(fileTransferRecordService.getRecordPage(
                current, size, dataSetId, categoryId, recordNo, operationType, recordStatus,
                originalName, extension, uploadStatus, createTimeStart, createTimeEnd));
    }

    @GetMapping("/{recordNo}")
    @Operation(summary = "查询文件传输记录详情")
    public ResponseResult<GisFileTransferRecordVO> detail(
            @Parameter(description = "传输批次编号", required = true)
            @PathVariable String recordNo) {
        return ResponseResult.success(fileTransferRecordService.getRecordDetail(recordNo));
    }

    @PostMapping("/{recordNo}/retry")
    @Operation(summary = "重试导入批次中已完整上传的失败文件", description = "按失败阶段重试元数据提取或矢量入库，复用原任务和图层")
    @OperationLog(module = "GIS_FILE_UPLOAD", type = OperationType.SUBMIT, description = "重试GIS数据导入")
    public ResponseResult<GisFileTransferRecordVO> retry(@PathVariable String recordNo) {
        return ResponseResult.success(fileUploadService.retryImport(recordNo));
    }
}
