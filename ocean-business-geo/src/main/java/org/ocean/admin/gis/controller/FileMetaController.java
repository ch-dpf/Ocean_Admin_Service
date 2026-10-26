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
            @ModelAttribute GisFileQuery query) {
        return ResponseResult.success(fileMetaService.getFileMetaPage(query));
    }

    @PostMapping("/{id}/metadata/retry")
    @Operation(summary = "重新提取文件空间元数据")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.SUBMIT, description = "重新提取GIS空间元数据")
    public ResponseResult<String> retryMetadata(@PathVariable Long id) {
        fileMetaService.retryMetadata(id);
        return ResponseResult.success("已提交元数据提取任务");
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
    @Operation(summary = "永久删除文件",
            description = "永久删除文件元数据和物理文件；活动处理任务引用时拒绝删除")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE, description = "永久删除GIS文件")
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
