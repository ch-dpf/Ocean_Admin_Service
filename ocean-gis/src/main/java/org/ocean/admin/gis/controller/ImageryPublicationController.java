package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.ImageryPublicationService;
import org.ocean.admin.gis.vo.GisImageryPublicationVO;
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

/** 影像瓦片服务发布管理接口。 */
@RestController
@RequestMapping("/api/gis/imagery-publications")
@Tag(name = "GIS影像发布", description = "发布、查询和停用 XYZ 影像瓦片服务")
@RequiredArgsConstructor
public class ImageryPublicationController {

    private final ImageryPublicationService imageryPublicationService;

    @GetMapping("/page")
    @Operation(summary = "分页查询影像发布记录")
    @OperationLog(module = "GIS_IMAGERY_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询GIS影像发布记录", recordResponse = true)
    public ResponseResult<PageResult<List<GisImageryPublicationVO>>> getPublicationPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String serviceCode,
            @Parameter(description = "发布状态：PUBLISHED、DISABLED")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishTimeStart,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishTimeEnd) {
        return ResponseResult.success(imageryPublicationService.getPublicationPage(
                current, size, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd));
    }

    @PostMapping("/{sourceTaskId}/publish")
    @Operation(summary = "发布已完成的影像切片任务")
    @OperationLog(module = "GIS_IMAGERY_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布GIS影像瓦片服务", recordResponse = true)
    public ResponseResult<GisImageryPublicationVO> publish(@PathVariable Long sourceTaskId) {
        return ResponseResult.success("影像服务发布成功",
                imageryPublicationService.publish(sourceTaskId));
    }

    @GetMapping("/{serviceCode}")
    @Operation(summary = "查询影像发布信息")
    public ResponseResult<GisImageryPublicationVO> get(@PathVariable String serviceCode) {
        return ResponseResult.success(imageryPublicationService.get(serviceCode));
    }

    @PutMapping("/{serviceCode}/disable")
    @Operation(summary = "停用影像服务")
    @OperationLog(module = "GIS_IMAGERY_PUBLICATION", type = OperationType.CANCEL,
            description = "停用GIS影像瓦片服务", recordResponse = true)
    public ResponseResult<GisImageryPublicationVO> disable(@PathVariable String serviceCode) {
        return ResponseResult.success("影像服务已停用",
                imageryPublicationService.disable(serviceCode));
    }
}
