package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.TerrainPublicationService;
import org.ocean.admin.gis.vo.GisTerrainPublicationVO;
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

/** 地形服务发布管理接口。 */
@RestController
@RequestMapping("/api/gis/terrain-publications")
@Tag(name = "GIS地形发布", description = "发布、查询和停用 Cesium 地形服务")
@RequiredArgsConstructor
public class TerrainPublicationController {

    private final TerrainPublicationService terrainPublicationService;

    @GetMapping("/page")
    @Operation(summary = "分页查询地形数据发布记录")
    @OperationLog(module = "GIS_TERRAIN_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询GIS地形发布记录", recordResponse = true)
    public ResponseResult<PageResult<List<GisTerrainPublicationVO>>> getPublicationPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
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
        return ResponseResult.success(terrainPublicationService.getPublicationPage(
                current, size, serviceCode, status, dataSetId, publishTimeStart, publishTimeEnd));
    }

    @PostMapping("/{sourceTaskId}/publish")
    @Operation(summary = "发布已完成的地形切片任务")
    @OperationLog(module = "GIS_TERRAIN_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布GIS地形服务", recordResponse = true)
    public ResponseResult<GisTerrainPublicationVO> publish(
            @PathVariable Long sourceTaskId) {
        return ResponseResult.success("地形服务发布成功",
                terrainPublicationService.publish(sourceTaskId));
    }

    @GetMapping("/{serviceCode}")
    @Operation(summary = "查询地形发布信息")
    public ResponseResult<GisTerrainPublicationVO> get(
            @PathVariable String serviceCode) {
        return ResponseResult.success(terrainPublicationService.get(serviceCode));
    }

    @PutMapping("/{serviceCode}/disable")
    @Operation(summary = "停用地形服务")
    @OperationLog(module = "GIS_TERRAIN_PUBLICATION", type = OperationType.CANCEL,
            description = "停用GIS地形服务", recordResponse = true)
    public ResponseResult<GisTerrainPublicationVO> disable(
            @PathVariable String serviceCode) {
        return ResponseResult.success("地形服务已停用",
                terrainPublicationService.disable(serviceCode));
    }
}
