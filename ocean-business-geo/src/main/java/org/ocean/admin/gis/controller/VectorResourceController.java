package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.VectorDataSetStyleService;
import org.ocean.admin.gis.service.VectorResourceService;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/** 矢量资源与存储管理接口。 */
@RestController
@RequestMapping("/api/gis/vector")
@RequiredArgsConstructor
@Tag(name = "GIS矢量资源")
public class VectorResourceController {
    private final VectorDataSetStyleService dataSetStyles;
    private final VectorResourceService resources;

    @DeleteMapping("/resources/{id}")
    @Operation(summary="删除无工作图层或发布引用的矢量存储版本")
    public ResponseResult<?> retireResource(@PathVariable Long id) {
        resources.retire(id);
        return ResponseResult.success("资源已进入清理流程");
    }

    @GetMapping("/source-layers/{id}/features")
    @Operation(summary = "分页浏览只读原始要素，坐标保持源坐标系")
    public ResponseResult<?> sourceFeatures(@PathVariable Long id, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) Long featureId) throws IOException {
        return ResponseResult.success(resources.sourceFeatures(id, after, limit, featureId));
    }

    @GetMapping("/datasets/{dataSetId}/resources")
    @Operation(summary = "查看原始快照、工作存储及发布版本")
    public ResponseEntity<?> resources(@PathVariable Long dataSetId) {
        var state = dataSetStyles.require(dataSetId, false);
        return ResponseEntity.ok().eTag("\"storage-" + dataSetId + "-" + state.getDataRevision() + "\"")
                .body(ResponseResult.success(resources.list(dataSetId)));
    }

    @PostMapping("/datasets/{dataSetId}/storage")
    @Operation(summary = "显式转换工作数据存储：GPKG或POSTGIS；仅工作版本可编辑")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.UPDATE, description = "转换矢量工作存储")
    public ResponseResult<?> convertStorage(@PathVariable Long dataSetId, @RequestParam String target,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) throws IOException {
        return ResponseResult.success(resources.convert(dataSetId, target, ifMatch));
    }
}
