package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.GisFileMetaService;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 地理文件数据管理
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Tag(name = "GIS文件元数据管理", description = "GIS文件元数据管理接口")
@RestController
@RequestMapping("/api/gis/filemeta")
@RequiredArgsConstructor
public class GisFileMetaController {

    private final GisFileMetaService gisFileMetaService;

    @GetMapping("/page")
    @Operation(summary = "分页查询文件元数据")
    public ResponseResult<PageResult<List<GisFileMetaVO>>> getFileMetaPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String uploadStatus) {
        return ResponseResult.success(gisFileMetaService.getFileMetaPage(
                current, size, dataSetId, categoryId, taskId, originalName, extension, uploadStatus));
    }

    @PostMapping("/create")
    @Operation(summary = "创建文件元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.INSERT, description = "创建GIS文件元数据",
            recordResponse = true)
    public ResponseResult<GisFileMetaVO> createFileMeta(@Valid @RequestBody GisFileMetaVO reqVO) {
        return ResponseResult.success("创建成功", gisFileMetaService.createFileMeta(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新文件元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.UPDATE, description = "更新GIS文件元数据")
    public ResponseResult<GisFileMetaVO> updateFileMeta(@Valid @RequestBody GisFileMetaVO reqVO) {
        return ResponseResult.success("更新成功", gisFileMetaService.updateFileMeta(reqVO));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE, description = "删除GIS文件元数据")
    public ResponseResult<String> deleteFileMeta(@PathVariable Long id) {
        gisFileMetaService.deleteFileMeta(id);
        return ResponseResult.success("删除成功");
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取文件元数据详情")
    public ResponseResult<GisFileMetaVO> getFileMeta(@PathVariable Long id) {
        return ResponseResult.success(gisFileMetaService.getFileMetaDetail(id));
    }
}
