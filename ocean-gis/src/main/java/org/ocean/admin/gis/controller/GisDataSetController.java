package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisDataSet;
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
@Tag(name = "Gis数据集管理", description = "Gis数据集管理接口")
@RequiredArgsConstructor
public class GisDataSetController {

    private final GisDataSetService gisDataSetService;

    @GetMapping("/page")
    @Operation(summary = "获取数据集分页")
    public ResponseResult<PageResult<List<GisDataSet>>> getDataSetPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(defaultValue = "0") Long categoryId,
            @RequestParam(defaultValue = "0") String dataSetName) {
        return ResponseResult.success(
                gisDataSetService.getDataSetPage(current, size, categoryId, dataSetName)
        );
    }

    @PostMapping("/create")
    @Operation(summary = "创建数据集")
    public ResponseResult<GisDataSetVO> createProject(@RequestBody GisDataSetVO reqVO) {
        GisDataSetVO created = gisDataSetService.createProject(reqVO);
        return ResponseResult.success("创建成功", created);
    }

    @PutMapping("/update")
    @Operation(summary = "更新数据集")
    public ResponseResult<String> updateProject(@RequestBody GisDataSetVO reqVO) {
        gisDataSetService.updateProject(reqVO);
        return ResponseResult.success("更新成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据集")
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
