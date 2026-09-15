package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.gis.service.GisDataSetService;
import org.ocean.admin.gis.vo.GisDataSetVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据集接口
 *
 * @author DeepOcean
 * @since 2026-09-11
 */
@RestController
@RequestMapping("/api/gis/dataSet")
@Tag(name = "GIS数据集管理", description = "GIS数据集管理接口")
@RequiredArgsConstructor
public class DataSetController {

    private final GisDataSetService gisDataSetService;

    @GetMapping("/page")
    @Operation(summary = "获取数据集分页")
    public ResponseResult<PageResult<List<GisDataSet>>> getDataSetPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String dataSetName) {
        return ResponseResult.success(
                gisDataSetService.getDataSetPage(current, size, categoryId, dataSetName)
        );
    }

    @PostMapping("/create")
    @Operation(summary = "创建数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.INSERT, description = "创建GIS数据集",
            recordResponse = true)
    public ResponseResult<GisDataSetVO> createProject(@RequestBody GisDataSetVO reqVO) {
        GisDataSetVO created = gisDataSetService.createProject(reqVO);
        return ResponseResult.success("创建成功", created);
    }

    @PutMapping("/update")
    @Operation(summary = "更新数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.UPDATE, description = "更新GIS数据集")
    public ResponseResult<String> updateProject(@RequestBody GisDataSetVO reqVO) {
        gisDataSetService.updateProject(reqVO);
        return ResponseResult.success("更新成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.DELETE, description = "删除GIS数据集")
    public ResponseResult<String> deleteProject(@PathVariable Long id) {
        gisDataSetService.deleteProject(id);
        return ResponseResult.success("删除成功");
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取数据集详情")
    public ResponseResult<GisDataSetVO> getProject(@PathVariable Long id) {
        return ResponseResult.success(gisDataSetService.getProjectDetail(id));
    }


}
