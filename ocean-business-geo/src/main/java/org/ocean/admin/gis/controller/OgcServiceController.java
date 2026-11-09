package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.OgcServicePublishRequest;
import org.ocean.admin.gis.service.OgcServicePublicationService;
import org.ocean.admin.gis.vo.GisOgcServiceVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/** WMTS、WMS、TMS 独立服务与动态矢量 OGC 服务的统一发布管理入口。 */
@RestController
@RequestMapping("/api/gis/ogc-services")
@Tag(name = "OGC服务发布", description = "按资源类型发布栅格 OGC 服务或动态矢量 OGC 服务")
@RequiredArgsConstructor
public class OgcServiceController {

    private final OgcServicePublicationService ogcServicePublicationService;

    @PostMapping("/publish")
    @Operation(summary = "按资源类型发布 OGC 服务",
            description = "RASTER 发布影像瓦片集；VECTOR 发布冻结数据与样式的动态矢量服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布 OGC 服务", recordResponse = true)
    public ResponseResult<GisOgcServiceVO> publish(
            @Valid @RequestBody OgcServicePublishRequest request) throws IOException {
        return ResponseResult.success("OGC 服务发布成功",
                ogcServicePublicationService.publish(request));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询独立 OGC 服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询 OGC 服务", recordResponse = true)
    public ResponseResult<PageResult<List<GisOgcServiceVO>>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "服务类型：WMTS、WMS、TMS")
            @RequestParam(required = false) String protocol,
            @Parameter(description = "发布状态：PUBLISHED、DISABLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "编号、服务名称或图层名称关键字")
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long dataSetId,
            @Parameter(description = "发布开始时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishTimeStart,
            @Parameter(description = "发布结束时间，ISO 日期时间格式")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishTimeEnd) {
        return ResponseResult.success(ogcServicePublicationService.page(
                current, size, protocol, status, keyword, dataSetId,
                publishTimeStart, publishTimeEnd));
    }

    @GetMapping("/{serviceCode}")
    @Operation(summary = "查询独立 OGC 服务详情")
    public ResponseResult<GisOgcServiceVO> get(@PathVariable String serviceCode) {
        return ResponseResult.success(ogcServicePublicationService.get(serviceCode));
    }

    @PutMapping("/{serviceCode}/disable")
    @Operation(summary = "停用独立 OGC 服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.CANCEL,
            description = "停用 OGC 服务", recordResponse = true)
    public ResponseResult<GisOgcServiceVO> disable(@PathVariable String serviceCode) {
        return ResponseResult.success("OGC 服务已停用",
                ogcServicePublicationService.disable(serviceCode));
    }

    @PutMapping("/{serviceCode}/enable")
    @Operation(summary = "启用独立 OGC 服务")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.UPDATE,
            description = "启用 OGC 服务", recordResponse = true)
    public ResponseResult<GisOgcServiceVO> enable(@PathVariable String serviceCode) {
        return ResponseResult.success("OGC 服务已启用",
                ogcServicePublicationService.enable(serviceCode));
    }
}
