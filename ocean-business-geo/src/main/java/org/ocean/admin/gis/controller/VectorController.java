package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.service.VectorImportService;
import org.ocean.admin.gis.service.VectorLayerService;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.gis.util.GeoJsonFeatureReader;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.PageResult;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.io.IOException;

/** 可编辑矢量数据入口；空间响应直接使用 GeoJSON，管理响应沿用统一包装。 */
@RestController
@RequestMapping("/api/gis/vector")
@RequiredArgsConstructor
@Tag(name = "GIS矢量要素管理", description = "GeoJSON导入、图层管理、空间查询与要素编辑")
public class VectorController {
    private final VectorImportService imports;
    private final VectorLayerService layers;
    private final GeoJsonFeatureReader reader;

    @PostMapping("/layers/import")
    @Operation(summary = "从已上传GeoJSON导入矢量图层", description = "首期 SINGLE 模式；异步执行，重复requestId返回原任务")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.SUBMIT, description = "导入矢量图层", recordResponse = true)
    public ResponseEntity<ResponseResult<GisVectorVO.ImportTask>> submit(@Valid @RequestBody GisVectorRequests.Import request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResponseResult.success(imports.submit(request)));
    }

    @GetMapping("/imports/{taskId}")
    @Operation(summary = "查询矢量导入任务状态及图层结果")
    public ResponseResult<GisVectorVO.ImportTask> importDetail(@PathVariable String taskId) {
        return ResponseResult.success(imports.detail(taskId));
    }

    @PostMapping("/imports/{taskId}/retry")
    @Operation(summary = "重试失败的矢量导入任务")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.SUBMIT, description = "重试矢量导入")
    public ResponseEntity<ResponseResult<GisVectorVO.ImportTask>> retry(@PathVariable String taskId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResponseResult.success(imports.retry(taskId)));
    }

    @GetMapping("/layers")
    @Operation(summary = "分页查询矢量图层")
    public ResponseResult<PageResult<List<GisVectorVO.Layer>>> page(
            @RequestParam(required = false) Long dataSetId,
            @RequestParam(defaultValue = "1") int current, @RequestParam(defaultValue = "10") int size) {
        return ResponseResult.success(layers.page(dataSetId, current, size));
    }

    @GetMapping("/layers/{layerId}")
    @Operation(summary = "查询图层详情", description = "返回ETag，删除图层时通过If-Match提交")
    public ResponseEntity<ResponseResult<GisVectorVO.Layer>> layer(@PathVariable Long layerId) {
        var layer = layers.detail(layerId);
        return ResponseEntity.ok().eTag(VectorLayerService.layerEtag(layerId, layer.dataVersion())).body(ResponseResult.success(layer));
    }

    @DeleteMapping("/layers/{layerId}")
    @Operation(summary = "删除图层", description = "仅可删除已结束导入的图层；逻辑删除后要素不再可查询")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.DELETE, description = "删除矢量图层")
    public ResponseResult<Void> deleteLayer(@PathVariable Long layerId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        layers.deleteLayer(layerId, ifMatch);
        return ResponseResult.success();
    }

    @GetMapping(value = "/layers/{layerId}/features", produces = "application/geo+json")
    @Operation(summary = "按真实要素几何查询GeoJSON", description = "bbox=west,south,east,north；limit为1至1000；后续页须保持bbox不变")
    public GisVectorVO.FeatureCollection features(@PathVariable Long layerId,
            @RequestParam(required = false) String bbox, @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String cursor) {
        return layers.features(layerId, bbox, limit, cursor);
    }

    @GetMapping(value = "/layers/{layerId}/features/{featureId}", produces = "application/geo+json")
    @Operation(summary = "查询完整要素及编辑ETag")
    public ResponseEntity<GisVectorVO.Feature> feature(@PathVariable Long layerId, @PathVariable Long featureId) {
        return featureResponse(layers.feature(layerId, featureId), HttpStatus.OK);
    }

    @PostMapping(value = "/layers/{layerId}/features", produces = "application/geo+json",
            consumes = {"application/json", "application/geo+json"})
    @Operation(summary = "新增GeoJSON Feature", description = "系统分配要素ID，properties须为对象或null",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/geo+json", schema = @Schema(type = "object",
                            example = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[110,20]},\"properties\":{\"name\":\"观测点\"}}"))))
    @OperationLog(module = "GIS_VECTOR", type = OperationType.INSERT, description = "新增矢量要素")
    public ResponseEntity<GisVectorVO.Feature> create(@PathVariable Long layerId, HttpServletRequest request) {
        return featureResponse(layers.createFeature(layerId, readFeature(request)), HttpStatus.CREATED);
    }

    @PutMapping(value = "/layers/{layerId}/features/{featureId}", produces = "application/geo+json",
            consumes = {"application/json", "application/geo+json"})
    @Operation(summary = "完整替换要素几何与属性", description = "须携带If-Match；冲突返回412，缺失返回428；保留原坐标维度",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/geo+json", schema = @Schema(type = "object",
                            example = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[110,20]},\"properties\":{\"name\":\"观测点\"}}"))))
    @OperationLog(module = "GIS_VECTOR", type = OperationType.UPDATE, description = "编辑矢量要素")
    public ResponseEntity<GisVectorVO.Feature> update(@PathVariable Long layerId, @PathVariable Long featureId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, HttpServletRequest request) {
        return featureResponse(layers.updateFeature(layerId, featureId, ifMatch, readFeature(request)), HttpStatus.OK);
    }

    @DeleteMapping("/layers/{layerId}/features/{featureId}")
    @Operation(summary = "删除要素", description = "须携带最新If-Match")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.DELETE, description = "删除矢量要素")
    public ResponseResult<Void> delete(@PathVariable Long layerId, @PathVariable Long featureId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        layers.deleteFeature(layerId, featureId, ifMatch);
        return ResponseResult.success();
    }

    private ResponseEntity<GisVectorVO.Feature> featureResponse(GisVectorVO.Feature feature, HttpStatus status) {
        return ResponseEntity.status(status).eTag(VectorLayerService.featureEtag(Long.valueOf(feature.id()), feature.version())).body(feature);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ResponseResult<Void>> handleVectorError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(ResponseResult.error(exception.getStatusCode().value(), exception.getReason()));
    }

    private JsonNode readFeature(HttpServletRequest request) {
        try { return reader.readFeature(request.getInputStream()); }
        catch (IOException | RuntimeException ex) {
            // JSON 解析器会包装输入流异常，仍需保留超限请求的 HTTP 语义。
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof GeoJsonFeatureReader.BodyTooLargeException) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, cause.getMessage());
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的Feature请求: " + ex.getMessage());
        }
    }
}
