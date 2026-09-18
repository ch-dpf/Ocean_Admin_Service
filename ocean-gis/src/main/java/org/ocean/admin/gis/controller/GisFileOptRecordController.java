package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileOptRecord;
import org.ocean.admin.gis.service.GisFileMetaService;
import org.ocean.admin.gis.service.GisFileOptRecordService;
import org.ocean.admin.gis.vo.GisFileMetaVO;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GIS 源数据文件操作记录
 * @author DeepOcean
 * @since 2026-09-16
 */
@RestController
@RequestMapping("/api/gis/fileOpt")
@Tag(name = "GIS文件操作记录", description = "GIS文件操作记录")
@RequiredArgsConstructor
public class GisFileOptRecordController {

    private final GisFileOptRecordService gisFileOptRecordService;

    // 上传导入记录
    @GetMapping("/uploadRecord")
    @Operation(summary = "分页查询上传记录")
    public ResponseResult<PageResult<List<GisFileOptRecord>>> uploadRecord(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        String opt = "";
        return gisFileOptRecordService.recordPages(current,size,opt);
    }





}
