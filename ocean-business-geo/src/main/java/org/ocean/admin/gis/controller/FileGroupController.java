package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.service.FileGroupService;
import org.ocean.admin.gis.vo.GisFileGroupVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 文件组管理接口，不改变物理文件下载及删除接口的语义。 */
@RestController
@RequestMapping("/api/gis/file-groups")
@Tag(name = "文件组管理")
@RequiredArgsConstructor
public class FileGroupController {
    private final FileGroupService service;

    @GetMapping("/page")
    @Operation(summary = "分页查询逻辑文件组，默认不展示重复输入追溯记录")
    public ResponseResult<PageResult<List<GisFileGroup>>> page(@ModelAttribute GisFileGroupVO.Query query) {
        return ResponseResult.success(service.page(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询文件组及完整成员")
    public ResponseResult<GisFileGroupVO> detail(@PathVariable Long id) {
        return ResponseResult.success(service.detail(id));
    }

    @PutMapping("/{id}/options")
    @Operation(summary = "设置源坐标系及CSV坐标字段")
    @OperationLog(module = "GIS_FILE_GROUP", type = OperationType.UPDATE, description = "设置文件组读取配置")
    public ResponseResult<String> options(@PathVariable Long id, @Valid @RequestBody GisFileGroupVO.Options options) {
        service.updateOptions(id, options);
        return ResponseResult.success("配置已保存");
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "显式重新提取文件组元数据")
    @OperationLog(module = "GIS_FILE_GROUP", type = OperationType.SUBMIT, description = "重新解析文件组")
    public ResponseResult<String> retry(@PathVariable Long id) {
        service.retry(id);
        return ResponseResult.success("已提交解析");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文件组及成员登记，工作区源文件原位保留")
    @OperationLog(module = "GIS_FILE_GROUP", type = OperationType.DELETE, description = "删除文件组")
    public ResponseResult<String> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseResult.success("删除成功");
    }

    @GetMapping("/formats")
    @Operation(summary = "查询入口格式及必需配套文件规则")
    public ResponseResult<List<Map<String, Object>>> formats() {
        return ResponseResult.success(Arrays.stream(GisFileFormat.values()).map(format -> Map.<String, Object>of(
                "format", format.name(), "vector", format.isVector(), "entryExtensions", format.entryExtensions(),
                "requiredExtensions", format.requiredExtensions(), "optionalExtensions", format.optionalExtensions(),
                "drivers", format.driverNames(), "requiresCoordinateOptions", format == GisFileFormat.CSV
                        || format == GisFileFormat.DXF || format == GisFileFormat.DWG)).toList());
    }
}
