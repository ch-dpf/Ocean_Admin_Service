package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.FileMetaService;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GIS数据管理
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Tag(name = "GIS文件元数据管理", description = "GIS文件元数据管理接口")
@RestController
@RequestMapping("/api/gis/filemeta")
@RequiredArgsConstructor
public class FileMetaController {

    private final FileMetaService fileMetaService;

    @GetMapping("/page")
    @Operation(summary = "分页查询文件元数据")
    public ResponseResult<PageResult<List<GisFileMetaVO>>> getFileMetaPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String uploadStatus) {
        return ResponseResult.success(fileMetaService.getFileMetaPage(
                current, size, dataSetId, categoryId, originalName, extension, uploadStatus));
    }

    @GetMapping("/deleted/page")
    @Operation(summary = "分页查询文件元数据(回收站)")
    public ResponseResult<PageResult<List<GisFileMetaVO>>> getDeletedFileMetaPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String originalName,
            @RequestParam(required = false) String extension,
            @RequestParam(required = false) String uploadStatus) {
        return ResponseResult.success(fileMetaService.getDeletedFileMetaPage(
                current, size, dataSetId, categoryId, originalName, extension, uploadStatus));
    }

    @PutMapping("/deleted/{id}/restore")
    @Operation(summary = "恢复回收站文件", description = "撤销文件元数据的逻辑删除状态")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.UPDATE,
            description = "恢复回收站GIS文件元数据")
    public ResponseResult<String> restoreDeletedFileMeta(@PathVariable Long id) {
        fileMetaService.restoreDeletedFileMeta(id);
        return ResponseResult.success("恢复成功");
    }

    @DeleteMapping("/deleted/{id}")
    @Operation(summary = "彻底删除回收站文件",
            description = "永久删除文件元数据及其对应的存储文件，此操作不可恢复")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE,
            description = "彻底删除回收站GIS文件")
    public ResponseResult<String> permanentlyDeleteFileMeta(@PathVariable Long id) {
        fileMetaService.permanentlyDeleteFileMeta(id);
        return ResponseResult.success("彻底删除成功");
    }

    @PostMapping("/create")
    @Operation(summary = "创建文件元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.INSERT, description = "创建GIS文件元数据",
            recordResponse = true)
    public ResponseResult<GisFileMetaVO> createFileMeta(@Valid @RequestBody GisFileMetaVO reqVO) {
        return ResponseResult.success("创建成功", fileMetaService.createFileMeta(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新文件元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.UPDATE, description = "更新GIS文件元数据")
    public ResponseResult<GisFileMetaVO> updateFileMeta(@Valid @RequestBody GisFileMetaVO reqVO) {
        return ResponseResult.success("更新成功", fileMetaService.updateFileMeta(reqVO));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE, description = "删除GIS文件元数据")
    public ResponseResult<String> deleteFileMeta(@PathVariable Long id) {
        fileMetaService.deleteFileMeta(id);
        return ResponseResult.success("删除成功");
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取文件元数据详情")
    public ResponseResult<GisFileMetaVO> getFileMeta(@PathVariable Long id) {
        return ResponseResult.success(fileMetaService.getFileMetaDetail(id));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载文件数据",
            description = "下载已入库文件的原始内容，支持单区间 Range 断点续传与 HEAD 探测")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.EXPORT, description = "下载GIS文件数据")
    public void downloadFile(
            @Parameter(description = "文件元数据ID", required = true)
            @PathVariable Long id,
            HttpServletRequest request,
            HttpServletResponse response) {
        fileMetaService.downloadFile(id, request, response);
    }

}
