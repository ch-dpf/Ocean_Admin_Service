package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.FileMetaService;
import org.ocean.admin.gis.dto.GisFileQuery;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * GEO数据管理
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Tag(name = "文件元数据管理", description = "文件元数据管理接口")
@RestController
@RequestMapping("/api/gis/filemeta")
@RequiredArgsConstructor
public class FileMetaController {

    private final FileMetaService fileMetaService;

    @GetMapping("/{id}/source-layers")
    @Operation(summary = "查看导入时原始要素集合，包含空集合")
    public ResponseResult<?> sourceLayers(@PathVariable Long id) {
        return ResponseResult.success(fileMetaService.sourceLayers(id));
    }

    @PostMapping("/{id}/source-snapshot")
    @Operation(summary = "生成原始集合快照，保留现有工作图层编辑结果")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.SUBMIT, description = "生成原始集合快照")
    public ResponseResult<?> snapshotSource(@PathVariable Long id) throws java.io.IOException {
        return ResponseResult.success(fileMetaService.snapshotSource(id));
    }

    @GetMapping("/{id}/release-preview")
    @Operation(summary = "检查原始文件组释放影响与阻塞原因")
    public ResponseResult<?> releasePreview(@PathVariable Long id) {
        return ResponseResult.success(fileMetaService.releasePreview(id));
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "释放原始物理文件，保留来源记录、要素快照与导入图层")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE, description = "释放原始文件组")
    public ResponseResult<?> release(@PathVariable Long id) {
        var result = fileMetaService.releaseRawFiles(id);
        fileMetaService.cleanupReleasedRawFiles();
        return ResponseResult.success(result);
    }

    @PostMapping("/workspace-files")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "服务器工作区接入数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.SUBMIT, description = "添加数据--工作区数据接入")
    public ResponseResult<GisFileMetaVO.WorkspaceSubmission> addWorkspaceFiles(
            @Parameter(description = "所属数据集ID", required = true)
            @RequestParam("dataSetId") Long dataSetId,
            @Valid @RequestBody GisFileMetaVO.WorkspaceRequest request) {
        return ResponseResult.success("数据添加成功，请查看进度",
                fileMetaService.addWorkspaceFiles(dataSetId, request));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询文件元数据")
    public ResponseResult<PageResult<List<GisFileMetaVO>>> getFileMetaPage(
            @ModelAttribute GisFileQuery query) {
        return ResponseResult.success(fileMetaService.getFileMetaPage(query));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除管理文件")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE, description = "删除文件")
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
    @Operation(summary = "下载文件数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.EXPORT, description = "下载文件数据")
    public void downloadFile(
            @Parameter(description = "文件元数据ID", required = true)
            @PathVariable Long id,
            HttpServletRequest request,
            HttpServletResponse response) {
        fileMetaService.downloadFile(id, request, response);
    }

}
