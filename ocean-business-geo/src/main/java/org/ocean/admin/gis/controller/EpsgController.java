package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.EpsgService;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 本地 EPSG 坐标系目录查询入口。 */
@RestController
@RequestMapping("/api/gis/epsg")
@Tag(name = "EPSG坐标系查询")
@RequiredArgsConstructor
public class EpsgController {
    private final EpsgService service;

    @GetMapping
    @Operation(summary = "按编码或名称分页搜索EPSG坐标系")
    public ResponseResult<PageResult<List<EpsgService.Summary>>> search(
            @RequestParam(defaultValue = "") @Size(max = 100) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) long current,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ResponseResult.success(service.search(keyword, current, size));
    }

    @GetMapping("/{code}")
    @Operation(summary = "查询EPSG坐标系定义及适用范围")
    public ResponseResult<EpsgService.Detail> detail(@PathVariable String code) {
        return ResponseResult.success(service.detail(code));
    }
}
