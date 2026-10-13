package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.ImageryPublicationService;
import org.ocean.admin.gis.service.PublicationService;
import org.ocean.admin.gis.service.TerrainPublicationService;
import org.ocean.admin.gis.vo.GisImageryPublicationVO;
import org.ocean.admin.gis.vo.GisPublicationVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 瓦片服务发布管理与统一记录查询入口。 */
@RestController
@Tag(name = "GIS服务发布", description = "地形、影像服务发布管理与统一记录查询")
@RequiredArgsConstructor
public class PublicationController {
    private final PublicationService publicationService;
    private final ImageryPublicationService imageryPublicationService;
    private final TerrainPublicationService terrainPublicationService;

    @GetMapping("/api/gis/publications/page")
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

    @GetMapping("/api/gis/imagery-publications/page")
    @Operation(summary = "分页查询影像发布记录")
    @OperationLog(module = "GIS_IMAGERY_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询GIS影像发布记录", recordResponse = true)
    public ResponseResult<PageResult<List<GisImageryPublicationVO>>> getImageryPublicationPage(
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

    @PostMapping("/api/gis/imagery-publications/{sourceTaskId}/publish")
    @Operation(summary = "发布已完成的影像切片任务")
    @OperationLog(module = "GIS_IMAGERY_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布GIS影像瓦片服务", recordResponse = true)
    public ResponseResult<GisImageryPublicationVO> publishImagery(
            @PathVariable Long sourceTaskId) {
        return ResponseResult.success("影像服务发布成功",
                imageryPublicationService.publish(sourceTaskId));
    }

    @GetMapping("/api/gis/imagery-publications/{serviceCode}")
    @Operation(summary = "查询影像发布信息")
    public ResponseResult<GisImageryPublicationVO> getImagery(
            @PathVariable String serviceCode) {
        return ResponseResult.success(imageryPublicationService.get(serviceCode));
    }

    @PutMapping("/api/gis/imagery-publications/{serviceCode}/disable")
    @Operation(summary = "停用影像服务")
    @OperationLog(module = "GIS_IMAGERY_PUBLICATION", type = OperationType.CANCEL,
            description = "停用GIS影像瓦片服务", recordResponse = true)
    public ResponseResult<GisImageryPublicationVO> disableImagery(
            @PathVariable String serviceCode) {
        return ResponseResult.success("影像服务已停用",
                imageryPublicationService.disable(serviceCode));
    }

    @GetMapping("/api/gis/terrain-publications/page")
    @Operation(summary = "分页查询地形数据发布记录")
    @OperationLog(module = "GIS_TERRAIN_PUBLICATION", type = OperationType.QUERY,
            description = "分页查询GIS地形发布记录", recordResponse = true)
    public ResponseResult<PageResult<List<GisTerrainPublicationVO>>> getTerrainPublicationPage(
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

    @PostMapping("/api/gis/terrain-publications/{sourceTaskId}/publish")
    @Operation(summary = "发布已完成的地形切片任务")
    @OperationLog(module = "GIS_TERRAIN_PUBLICATION", type = OperationType.SUBMIT,
            description = "发布GIS地形服务", recordResponse = true)
    public ResponseResult<GisTerrainPublicationVO> publishTerrain(
            @PathVariable Long sourceTaskId) {
        return ResponseResult.success("地形服务发布成功",
                terrainPublicationService.publish(sourceTaskId));
    }

    @GetMapping("/api/gis/terrain-publications/{serviceCode}")
    @Operation(summary = "查询地形发布信息")
    public ResponseResult<GisTerrainPublicationVO> getTerrain(
            @PathVariable String serviceCode) {
        return ResponseResult.success(terrainPublicationService.get(serviceCode));
    }

    @PutMapping("/api/gis/terrain-publications/{serviceCode}/disable")
    @Operation(summary = "停用地形服务")
    @OperationLog(module = "GIS_TERRAIN_PUBLICATION", type = OperationType.CANCEL,
            description = "停用GIS地形服务", recordResponse = true)
    public ResponseResult<GisTerrainPublicationVO> disableTerrain(
            @PathVariable String serviceCode) {
        return ResponseResult.success("地形服务已停用",
                terrainPublicationService.disable(serviceCode));
    }
}
