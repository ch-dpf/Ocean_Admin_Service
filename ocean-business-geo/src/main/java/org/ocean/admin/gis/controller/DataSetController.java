package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.ocean.admin.gis.service.FileMetaService;
import org.ocean.admin.gis.vo.GisFileMetaVO;
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
@Tag(name = "GIS数据集管理", description = "GIS数据集管理接口")
@RequiredArgsConstructor
public class DataSetController {

    private final DataSetService dataSetService;
    private final FileMetaService fileMetaService;

    @PostMapping("/{id}/workspace-files")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "从受控服务器工作区接入数据",
            description = "自动识别文件或目录，扫描登记收口后再异步提取空间元信息；progress快照的activeFiles提供并行文件解析工作量。原位引用，不创建传输记录")
    @OperationLog(module = "GIS_DATASET", type = OperationType.SUBMIT, description = "工作区数据接入")
    public ResponseResult<GisFileMetaVO.WorkspaceSubmission> addWorkspaceFiles(
            @PathVariable Long id, @Valid @RequestBody GisFileMetaVO.WorkspaceRequest request) {
        return ResponseResult.success("工作区接入已受理，请按progress.progressId跟踪进度",
                fileMetaService.addWorkspaceFiles(id, request));
    }

    @GetMapping("/page")
    @Operation(summary = "获取数据集分页")
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
    @OperationLog(module = "GIS_DATASET", type = OperationType.INSERT, description = "创建GIS数据集",
            recordResponse = true)
    public ResponseResult<GisDataSetVO> createDataset(@RequestBody GisDataSetVO reqVO) {
        GisDataSetVO created = dataSetService.createDataset(reqVO);
        return ResponseResult.success("创建成功", created);
    }

    @PutMapping("/update")
    @Operation(summary = "更新数据集")
    @OperationLog(module = "GIS_DATASET", type = OperationType.UPDATE, description = "更新GIS数据集")
    public ResponseResult<String> updateDataset(@RequestBody GisDataSetVO reqVO) {
        dataSetService.updateDataset(reqVO);
        return ResponseResult.success("更新成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据集",
            description = "删除数据集、托管物理文件及自有矢量数据；工作区源文件保留")
    @OperationLog(module = "GIS_DATASET", type = OperationType.DELETE, description = "删除GIS数据集")
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
    @Operation(summary = "获取数据集详情（含文件覆盖轮廓）")
    public ResponseResult<GisDataSetVO> getDatasetFootprints(
            @PathVariable Long id,
            @Parameter(description = "拓扑保持简化容差，单位为度，范围0至1；0返回完整几何")
            @RequestParam(defaultValue = "0.001") double simplifyTolerance) {
        return ResponseResult.success(dataSetService.getDatasetFootprints(id, simplifyTolerance));
    }


}
