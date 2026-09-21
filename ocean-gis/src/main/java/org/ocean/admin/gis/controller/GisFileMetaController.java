package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisFileProcessRequest;
import org.ocean.admin.gis.service.FileUploadService;
import org.ocean.admin.gis.service.ProcessingService;
import org.ocean.admin.gis.service.GisFileMetaService;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.gis.vo.GisProcessingTaskVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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
public class GisFileMetaController {

    private final GisFileMetaService gisFileMetaService;
    private final ProcessingService processingService;
    private final FileUploadService fileUploadService;

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
        return ResponseResult.success(gisFileMetaService.getFileMetaPage(
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
        return ResponseResult.success(gisFileMetaService.getDeletedFileMetaPage(
                current, size, dataSetId, categoryId, originalName, extension, uploadStatus));
    }

    @PutMapping("/deleted/{id}/restore")
    @Operation(summary = "恢复回收站文件", description = "撤销文件元数据的逻辑删除状态")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.UPDATE,
            description = "恢复回收站GIS文件元数据")
    public ResponseResult<String> restoreDeletedFileMeta(@PathVariable Long id) {
        gisFileMetaService.restoreDeletedFileMeta(id);
        return ResponseResult.success("恢复成功");
    }

    @DeleteMapping("/deleted/{id}")
    @Operation(summary = "彻底删除回收站文件",
            description = "永久删除文件元数据及其对应的存储文件，此操作不可恢复")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.DELETE,
            description = "彻底删除回收站GIS文件")
    public ResponseResult<String> permanentlyDeleteFileMeta(@PathVariable Long id) {
        gisFileMetaService.permanentlyDeleteFileMeta(id);
        return ResponseResult.success("彻底删除成功");
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

    @GetMapping("/{id}/download")
    @Operation(summary = "下载文件数据", description = "下载已入库文件的原始内容")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.EXPORT, description = "下载GIS文件数据")
    public void downloadFile(
            @Parameter(description = "文件元数据ID", required = true)
            @PathVariable Long id,
            HttpServletResponse response) {
        gisFileMetaService.downloadFile(id, response);
    }

    @PostMapping("/import/task")
    @Operation(summary = "创建GIS文件导入任务", description = "创建Redis上传会话并返回进度任务ID")
    public ResponseResult<Map<String, Object>> createImportTask(
            @Parameter(description = "数据集ID", required = true)
            @RequestParam Long dataSetId,
            @Parameter(description = "预计导入文件数量", required = true)
            @RequestParam Integer totalCount) {
        return ResponseResult.success(fileUploadService.importTaskRegistry(dataSetId, totalCount));
    }

    @PostMapping(value = "/import/batch/{taskId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "导入gis文件数据", description = "消费上传会话并同步存储GIS文件及元数据")
    @OperationLog(module = "GIS_FILE_UPLOAD", type = OperationType.SUBMIT,
            description = "批量上传GIS文件", recordResponse = true)
    public ResponseResult<Map<String, Object>> importBatch(
            @Parameter(description = "导入任务ID", required = true)
            @PathVariable String taskId,
            @Parameter(description = "文件列表", required = true)
            @RequestPart("files") List<MultipartFile> files){
        return ResponseResult.success(
                fileUploadService.importBatch(taskId, files));
    }

    @PostMapping("/{id}/process")
    @Operation(summary = "处理元数据",
            description = "按 TERRAIN、IMAGERY 或 VECTOR 选择处理引擎；任务异步执行；提交已入库单文件切片任务")
    @OperationLog(module = "GIS_FILE_META", type = OperationType.SUBMIT,
            description = "提交GIS文件切片任务", recordResponse = true)
    public ResponseResult<GisProcessingTaskVO> processFile(
            @PathVariable Long id,
            @Valid @RequestBody GisFileProcessRequest request) {
        return ResponseResult.success("处理任务已提交",
                processingService.submitSingle(id, request));
    }
}
