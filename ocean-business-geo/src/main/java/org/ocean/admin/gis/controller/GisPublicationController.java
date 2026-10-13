package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.GisPublicationService;
import org.ocean.admin.gis.vo.GisPublicationVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 所有类型瓦片服务的统一发布记录查询接口。 */
@RestController
@RequestMapping("/api/gis/publications")
@Tag(name = "GIS统一发布查询", description = "分页查看地形、影像和矢量发布记录")
@RequiredArgsConstructor
public class GisPublicationController {
    private final GisPublicationService publicationService;

    @GetMapping("/page")
    @Operation(summary = "分页查询所有GIS发布记录")
    @OperationLog(module = "GIS_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询所有GIS发布记录", recordResponse = true)
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
        return ResponseResult.success(publicationService.getAllPublicationPage(
                current, size, processingType, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd));
    }
}
