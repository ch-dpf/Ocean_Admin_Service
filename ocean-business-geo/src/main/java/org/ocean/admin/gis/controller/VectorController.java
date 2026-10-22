package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.service.VectorLayerService;
import org.ocean.admin.gis.service.VectorStyleService;
import org.ocean.admin.gis.service.VectorTileService;
import org.ocean.admin.gis.service.VectorGeneralizationService;
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
@Tag(name = "GIS矢量要素管理", description = "图层管理、空间查询与要素编辑")
public class VectorController {
    private final VectorLayerService layers;
    private final GeoJsonFeatureReader reader;
    private final VectorStyleService styles;
    private final VectorTileService tiles;
    private final VectorGeneralizationService generalization;

    @GetMapping("/layers/{layerId}/generalization")
    @Operation(summary="读取概化策略、构建状态和发布版本")
    public ResponseEntity<ResponseResult<GisVectorVO.Generalization>> generalization(@PathVariable Long layerId) {
        return generalizationResponse(generalization.detail(layerId),HttpStatus.OK);
    }

    @PutMapping("/layers/{layerId}/generalization")
    @Operation(summary="保存概化策略并异步构建",description="携带策略If-Match；只影响派生显示数据")
    @OperationLog(module="GIS_VECTOR",type=OperationType.UPDATE,description="修改矢量概化策略")
    public ResponseEntity<ResponseResult<GisVectorVO.Generalization>> saveGeneralization(@PathVariable Long layerId,
            @RequestHeader(value="If-Match",required=false) String ifMatch,@Valid @RequestBody GisVectorRequests.Generalization body) {
        return generalizationResponse(generalization.save(layerId,ifMatch,body.policy()),HttpStatus.ACCEPTED);
    }

    @PostMapping("/layers/{layerId}/generalization/builds")
    @Operation(summary="提交概化重建",description="排队中的同图层构建自动合并；携带策略If-Match")
    @OperationLog(module="GIS_VECTOR",type=OperationType.SUBMIT,description="重建矢量概览")
    public ResponseEntity<ResponseResult<GisVectorVO.Generalization>> rebuildGeneralization(@PathVariable Long layerId,
            @RequestHeader(value="If-Match",required=false) String ifMatch) {
        return generalizationResponse(generalization.rebuild(layerId,ifMatch),HttpStatus.ACCEPTED);
    }

    @GetMapping("/layers/{layerId}/representations/{representationId}/features")
    @Operation(summary="分页查询概化对象的原始要素成员")
    public ResponseResult<GisVectorVO.GeneralizationMembers> representationMembers(@PathVariable Long layerId,
            @PathVariable String representationId,@RequestParam String contextId,@RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) String cursor) {
        return ResponseResult.success(tiles.getRepresentationFeatures(layerId,contextId,representationId,limit,cursor));
    }

    private ResponseEntity<ResponseResult<GisVectorVO.Generalization>> generalizationResponse(GisVectorVO.Generalization state,HttpStatus status) {
        return ResponseEntity.status(status).eTag(VectorGeneralizationService.etag(Long.valueOf(state.layerId()),Long.parseLong(state.policyVersion())))
                .body(ResponseResult.success(state));
    }

    @PostMapping("/layers/{layerId}/render-contexts")
    @Operation(summary = "创建版本化矢量渲染上下文", description = "fields 按字面取属性；聚合以投影后的属性分组，保留 null 与缺失语义")
    public ResponseResult<GisVectorVO.RenderContext> renderContext(@PathVariable Long layerId,
            @Valid @RequestBody GisVectorRequests.RenderContext request) {
        return ResponseResult.success(tiles.createContext(layerId,request));
    }

    @GetMapping(value = "/layers/{layerId}/tiles/{z}/{x}/{y}.mvt", produces = "application/vnd.mapbox-vector-tile")
    @Operation(summary = "按层级读取显示用MVT矢量瓦片", description = "须携带contextId；旧数据版本409，上下文过期410；不截取任意前N个要素")
    public ResponseEntity<byte[]> tile(@PathVariable Long layerId, @PathVariable int z, @PathVariable int x,
            @PathVariable int y, @RequestParam String contextId) {
        byte[] body = tiles.getTile(layerId,contextId,z,x,y);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(body);
    }

    @GetMapping("/layers/{layerId}/clusters/{clusterId}/features")
    @Operation(summary = "分页查询聚合的原始要素成员", description = "计数按点位，成员按原始要素ID去重；编辑须再获取原始Feature及ETag")
    public ResponseResult<GisVectorVO.ClusterMembers> clusterFeatures(@PathVariable Long layerId,
            @PathVariable String clusterId, @RequestParam String contextId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
        return ResponseResult.success(tiles.getClusterFeatures(layerId,contextId,clusterId,limit,cursor));
    }

    @GetMapping("/layers/{layerId}/styles")
    @Operation(summary = "查询图层样式方案摘要", description = "样式定义通过单方案详情读取")
    public ResponseResult<List<GisVectorVO.StyleSummary>> styles(@PathVariable Long layerId) {
        return ResponseResult.success(styles.list(layerId));
    }

    @PostMapping("/layers/{layerId}/styles")
    @Operation(summary = "创建图层样式方案", description = "definition.schemaVersion=1；rules 有序匹配，返回独立样式ETag")
    @OperationLog(module = "GIS_VECTOR_STYLE", type = OperationType.INSERT, description = "创建矢量样式")
    public ResponseEntity<ResponseResult<GisVectorVO.Style>> createStyle(@PathVariable Long layerId,
            @Valid @RequestBody GisVectorRequests.Style request) {
        return styleResponse(styles.create(layerId, request), HttpStatus.CREATED);
    }

    @GetMapping("/layers/{layerId}/styles/{styleId}")
    @Operation(summary = "查询样式定义及ETag")
    public ResponseEntity<ResponseResult<GisVectorVO.Style>> style(@PathVariable Long layerId, @PathVariable Long styleId) {
        return styleResponse(styles.detail(layerId, styleId), HttpStatus.OK);
    }

    @GetMapping("/layers/{layerId}/default-style")
    @Operation(summary = "读取图层默认样式及ETag", description = "样式与GeoJSON分别读取，前端依据协议本地渲染")
    public ResponseEntity<ResponseResult<GisVectorVO.Style>> defaultStyle(@PathVariable Long layerId) {
        return styleResponse(styles.defaultStyle(layerId), HttpStatus.OK);
    }

    @PutMapping("/layers/{layerId}/styles/{styleId}")
    @Operation(summary = "完整替换样式方案", description = "If-Match 须来自该样式详情；不修改要素数据版本")
    @OperationLog(module = "GIS_VECTOR_STYLE", type = OperationType.UPDATE, description = "编辑矢量样式")
    public ResponseEntity<ResponseResult<GisVectorVO.Style>> updateStyle(@PathVariable Long layerId, @PathVariable Long styleId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @Valid @RequestBody GisVectorRequests.Style request) {
        return styleResponse(styles.update(layerId, styleId, ifMatch, request), HttpStatus.OK);
    }

    @PutMapping("/layers/{layerId}/styles/{styleId}/default")
    @Operation(summary = "切换图层默认样式", description = "If-Match 使用目标方案的ETag；原默认与新默认方案的版本同时更新")
    @OperationLog(module = "GIS_VECTOR_STYLE", type = OperationType.UPDATE, description = "切换矢量默认样式")
    public ResponseEntity<ResponseResult<GisVectorVO.Style>> setDefaultStyle(@PathVariable Long layerId, @PathVariable Long styleId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return styleResponse(styles.setDefault(layerId, styleId, ifMatch), HttpStatus.OK);
    }

    @DeleteMapping("/layers/{layerId}/styles/{styleId}")
    @Operation(summary = "删除非默认样式方案", description = "须携带最新If-Match；默认方案须先切换再删除")
    @OperationLog(module = "GIS_VECTOR_STYLE", type = OperationType.DELETE, description = "删除矢量样式")
    public ResponseResult<Void> deleteStyle(@PathVariable Long layerId, @PathVariable Long styleId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        styles.delete(layerId, styleId, ifMatch);
        return ResponseResult.success();
    }

    @PostMapping("/layers/{layerId}/styles/{styleId}/match")
    @Operation(summary = "预览指定要素在某层级的样式匹配", description = "提供 featureId、zoom；GeometryCollection 按路径返回结果，保留原要素ID")
    public ResponseResult<GisVectorVO.StyleMatch> matchStyle(@PathVariable Long layerId, @PathVariable Long styleId,
            @Valid @RequestBody GisVectorRequests.StyleMatch request) {
        return ResponseResult.success(styles.match(layerId, styleId, request));
    }

    private ResponseEntity<ResponseResult<GisVectorVO.Style>> styleResponse(GisVectorVO.Style style, HttpStatus status) {
        return ResponseEntity.status(status).eTag(VectorStyleService.etag(style.styleId(), style.version()))
                .body(ResponseResult.success(style));
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
