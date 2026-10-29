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
    @Operation(summary = "下载文件数据",
            description = "下载已入库文件的原始内容，支持单区间 Range 断点续传与 HEAD 探测")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.EXPORT, description = "下载文件数据")
    public void downloadFile(
            @Parameter(description = "文件元数据ID", required = true)
            @PathVariable Long id,
            HttpServletRequest request,
            HttpServletResponse response) {
        fileMetaService.downloadFile(id, request, response);
    }

}
