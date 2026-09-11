package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.service.FileUploadService;
import org.ocean.admin.gis.vo.GisUploadTaskVO;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * GIS任务管理
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@Slf4j
@Tag(name = "GIS任务管理", description = "GIS任务管理接口")
@RestController
@RequestMapping("/api/gis/task")
@RequiredArgsConstructor
public class GisTaskController {

    private final FileUploadService fileUploadService;

    @PostMapping(value = "/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "多文件上传", description = "一次上传多个Gis文件")
    public ResponseResult<GisUploadTaskVO> upload(
            @Parameter(description = "数据集ID") @RequestParam Long dataSetId,
            @Parameter(description = "文件列表", required = true)  @RequestPart("files") List<MultipartFile> files){
        return ResponseResult.success(
                fileUploadService.createUploadTask(dataSetId, files));
    }
}
