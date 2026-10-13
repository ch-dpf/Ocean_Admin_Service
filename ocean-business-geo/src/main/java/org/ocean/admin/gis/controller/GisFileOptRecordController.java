package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.GisFileOptRecordService;
import org.ocean.admin.gis.vo.GisFileOptRecordVO;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GIS 文件操作记录。
 */
@RestController
@RequestMapping("/api/gis/fileOpt")
@Tag(name = "GIS文件操作记录", description = "GIS文件操作记录")
@RequiredArgsConstructor
public class GisFileOptRecordController {

    private final GisFileOptRecordService gisFileOptRecordService;

    @GetMapping({"/page", "/uploadRecord"})
    @Operation(summary = "分页查询文件操作记录")
    public ResponseResult<PageResult<List<GisFileOptRecordVO>>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String recordNo,
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
        return ResponseResult.success(gisFileOptRecordService.getImportRecordPage(
                current, size, dataSetId, categoryId, recordNo, recordStatus,
                originalName, extension, uploadStatus, createTimeStart, createTimeEnd));
    }

    @GetMapping("/{recordNo}")
    @Operation(summary = "查询文件操作记录详情")
    public ResponseResult<GisFileOptRecordVO> detail(
            @Parameter(description = "操作批次编号", required = true)
            @PathVariable String recordNo) {
        return ResponseResult.success(gisFileOptRecordService.getImportRecordDetail(recordNo));
    }
}
