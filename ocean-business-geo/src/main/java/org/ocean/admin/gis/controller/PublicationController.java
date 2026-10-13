package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationService;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** GIS 服务发布的统一管理入口。 */
@RestController
@RequestMapping("/api/gis/publications")
@Tag(name = "GIS服务发布", description = "地形、影像服务的统一发布管理")
@RequiredArgsConstructor
public class PublicationController {

    private final PublicationService publicationService;

    @GetMapping("/page")
    @Operation(summary = "分页查询GIS发布记录")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询GIS发布记录", recordResponse = true)
    public ResponseResult<PageResult<List<GisPublicationVO>>> getPublicationPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "发布类型：TERRAIN、IMAGERY、VECTOR")
            @RequestParam(required = false) String processingType,
            @RequestParam(required = false) String serviceCode,
            @Parameter(description = "发布状态：PUBLISHED、DISABLED")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dataSetId,
            @Parameter(description = "发布开始时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishTimeStart,
            @Parameter(description = "发布结束时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishTimeEnd) {
        return ResponseResult.success(publicationService.getPublicationPage(
                current, size, processingType, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd));
    }

    @PostMapping("/{processingType}/{sourceTaskId}/publish")
    @Operation(summary = "发布已完成的瓦片任务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布GIS瓦片服务", recordResponse = true)
    public ResponseResult<GisPublicationVO> publish(
            @Parameter(description = "发布类型：TERRAIN 或 IMAGERY")
            @PathVariable GisProcessingType processingType,
            @PathVariable Long sourceTaskId) {
        return ResponseResult.success("GIS服务发布成功",
                publicationService.publish(processingType, sourceTaskId));
    }

    @GetMapping("/{processingType}/{serviceCode}")
    @Operation(summary = "查询GIS发布信息")
    public ResponseResult<GisPublicationVO> get(
            @Parameter(description = "发布类型：TERRAIN 或 IMAGERY")
            @PathVariable GisProcessingType processingType,
            @PathVariable String serviceCode) {
        return ResponseResult.success(publicationService.get(processingType, serviceCode));
    }

    @PutMapping("/{processingType}/{serviceCode}/disable")
    @Operation(summary = "停用GIS服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.CANCEL,
            description = "停用GIS瓦片服务", recordResponse = true)
    public ResponseResult<GisPublicationVO> disable(
            @Parameter(description = "发布类型：TERRAIN 或 IMAGERY")
            @PathVariable GisProcessingType processingType,
            @PathVariable String serviceCode) {
        return ResponseResult.success("GIS服务已停用",
                publicationService.disable(processingType, serviceCode));
    }
}
