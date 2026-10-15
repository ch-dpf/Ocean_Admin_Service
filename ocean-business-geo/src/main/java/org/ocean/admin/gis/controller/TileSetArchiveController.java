package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisCreateTileSetArchiveRequest;
import org.ocean.admin.gis.service.TileSetArchiveService;
import org.ocean.admin.gis.vo.GisTileSetArchiveVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 静态瓦片集成果归档管理入口。 */
@RestController
@RequestMapping("/api/gis/tile-set-archives")
@Tag(name = "GIS静态瓦片集归档", description = "处理成果归档、查询与下载接口")
@RequiredArgsConstructor
public class TileSetArchiveController {
    private final TileSetArchiveService archiveService;

    @PostMapping
    @Operation(summary = "创建静态瓦片集归档")
    @OperationLog(module = "GIS_TILE_SET_ARCHIVE", type = OperationType.SUBMIT,
            description = "创建GIS静态瓦片集归档", recordResponse = true)
    public ResponseResult<GisTileSetArchiveVO> create(
            @Valid @RequestBody GisCreateTileSetArchiveRequest request) {
        return ResponseResult.success("静态瓦片集归档任务已提交",
                archiveService.createArchive(request));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询静态瓦片集归档")
    @OperationLog(module = "GIS_TILE_SET_ARCHIVE", type = OperationType.QUERY,
            description = "分页查询GIS静态瓦片集归档", recordResponse = true)
    public ResponseResult<PageResult<List<GisTileSetArchiveVO>>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String archiveNo,
            @RequestParam(required = false) String taskNo,
            @RequestParam(required = false) String taskName,
            @Parameter(description = "处理类型：TERRAIN、IMAGERY、VECTOR")
            @RequestParam(required = false) String processingType,
            @Parameter(description = "归档状态：QUEUED、RUNNING、COMPLETED、FAILED")
            @RequestParam(required = false) String archiveStatus,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createTimeStart,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createTimeEnd) {
        return ResponseResult.success(archiveService.getArchivePage(
                current, size, archiveNo, taskNo, taskName, processingType,
                archiveStatus, createTimeStart, createTimeEnd));
    }

    @GetMapping("/{archiveNo}")
    @Operation(summary = "查询静态瓦片集归档详情")
    @OperationLog(module = "GIS_TILE_SET_ARCHIVE", type = OperationType.QUERY,
            description = "查询GIS静态瓦片集归档详情", recordResponse = true)
    public ResponseResult<GisTileSetArchiveVO> detail(@PathVariable String archiveNo) {
        return ResponseResult.success(archiveService.getArchiveDetail(archiveNo));
    }

    @PostMapping("/{archiveNo}/retry")
    @Operation(summary = "重试失败的静态瓦片集归档")
    @OperationLog(module = "GIS_TILE_SET_ARCHIVE", type = OperationType.SUBMIT,
            description = "重试GIS静态瓦片集归档", recordResponse = true)
    public ResponseResult<GisTileSetArchiveVO> retry(@PathVariable String archiveNo) {
        return ResponseResult.success("静态瓦片集归档已重新提交",
                archiveService.retryArchive(archiveNo));
    }

    @GetMapping("/{archiveNo}/download")
    @Operation(summary = "下载静态瓦片集成果包",
            description = "支持单区间 Range 断点续传与 HEAD 探测")
    @OperationLog(module = "GIS_TILE_SET_ARCHIVE", type = OperationType.EXPORT,
            description = "下载GIS静态瓦片集成果包")
    public void download(
            @PathVariable String archiveNo,
            HttpServletRequest request,
            HttpServletResponse response) {
        archiveService.downloadArchive(archiveNo, request, response);
    }
}
