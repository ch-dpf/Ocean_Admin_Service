package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.gis.service.DataSetService;
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
@Tag(name = "数据集管理", description = "数据集管理接口")
@RequiredArgsConstructor
public class DataSetController {

    private final DataSetService dataSetService;

    @GetMapping("/categories")
    @Operation(summary = "获取数据集分类及数量", description = "统计全部未删除数据集，不受名称筛选和分页影响")
    public ResponseResult<GisDataSetVO.CategorySummary> getDataSetCategories() {
        return ResponseResult.success(dataSetService.getDataSetCategories());
    }

    @GetMapping("/page")
    @Operation(summary = "获取数据集分页", description = "仅返回数据集基本信息及文件数、文件组数、总大小；不查询文件点位、轮廓或解析状态汇总")
    public ResponseResult<PageResult<List<GisDataSetVO>>> getDataSetPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String dataSetName) {
        return ResponseResult.success(
                dataSetService.getDataSetPage(current, size, categoryId, dataSetName)
        );
    }
    @GetMapping("/query")
    @Operation(summary = "获取全部数据集列表")
    public ResponseResult<List<GisDataSetVO>> getDataSets(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String dataSetName) {
        return ResponseResult.success(
                dataSetService.getDataSets(categoryId, dataSetName)
        );
    }

    @PostMapping("/create")
    @Operation(summary = "创建数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.INSERT, description = "创建数据集",
            recordResponse = true)
    public ResponseResult<GisDataSetVO> createDataset(@RequestBody GisDataSetVO reqVO) {
        GisDataSetVO created = dataSetService.createDataset(reqVO);
        return ResponseResult.success("创建成功", created);
    }

    @PutMapping("/update")
    @Operation(summary = "更新数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.UPDATE, description = "更新数据集")
    public ResponseResult<String> updateDataset(@RequestBody GisDataSetVO reqVO) {
        dataSetService.updateDataset(reqVO);
        return ResponseResult.success("更新成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.DELETE, description = "删除数据集")
    public ResponseResult<String> deleteDataset(@PathVariable Long id) {
        dataSetService.deleteDataset(id);
        return ResponseResult.success("删除成功");
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取数据集详情")
    public ResponseResult<GisDataSetVO> getDataset(@PathVariable Long id) {
        return ResponseResult.success(dataSetService.getDatasetDetail(id));
    }

    @GetMapping("/{id}/footprints")
    @Operation(summary = "获取数据集详情（合并覆盖范围）", description = "返回数据集级范围并集；矢量为包围范围，栅格为有效像元范围。预览精度以下的细缝、孔洞或分块可能消失")
    public ResponseResult<GisDataSetVO> getDatasetFootprints(
            @PathVariable Long id,
            @Parameter(description = "预览简化容差及精度对齐网格，单位为度，范围0至1；默认0.001。0执行完整精度合并，复杂轮廓可能耗时较长")
            @RequestParam(defaultValue = "0.001") double simplifyTolerance) {
        return ResponseResult.success(dataSetService.getDatasetFootprints(id, simplifyTolerance));
    }


}
