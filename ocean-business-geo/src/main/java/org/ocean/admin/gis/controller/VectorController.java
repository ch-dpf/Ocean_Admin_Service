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
import org.ocean.admin.gis.service.VectorDataSetStyleService;
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
    private final VectorDataSetStyleService dataSetStyles;

    @GetMapping("/datasets/{dataSetId}/style")
    @Operation(summary = "查询数据集样式快照与默认规则", description = "仅配置，不含要素几何")
    public ResponseResult<GisVectorVO.DataSetStyle> dataSetStyle(@PathVariable Long dataSetId) {
        return ResponseResult.success(dataSetStyles.detail(dataSetId));
    }

    @GetMapping("/datasets/{dataSetId}/groups")
    @Operation(summary = "查询数据集分类树与统计", description = "图层通过分页接口按分类加载，不内嵌几何")
    public ResponseEntity<ResponseResult<List<GisVectorVO.Group>>> groups(@PathVariable Long dataSetId) {
        var tree = dataSetStyles.groupTree(dataSetId);
        return ResponseEntity.ok().eTag(tree.etag()).body(ResponseResult.success(tree.groups()));
    }

    @PatchMapping("/datasets/{dataSetId}/groups/{groupId}/visibility")
    @Operation(summary = "更新分类默认显隐", description = "If-Match来自分类树响应；父分类关闭后所有后代均隐藏")
    @OperationLog(module = "GIS_VECTOR_STYLE", type = OperationType.UPDATE, description = "更新矢量分类显隐")
    public ResponseResult<Void> groupVisibility(@PathVariable Long dataSetId, @PathVariable Long groupId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody GisVectorRequests.GroupVisibility request) {
        dataSetStyles.setEnabled(dataSetId, groupId, request.enabled(), ifMatch);
        return ResponseResult.success();
    }

    @PutMapping("/layers/{layerId}/presentation")
    @Operation(summary = "原子保存图层挂载、样式与显隐", description = "If-Match来自图层详情；不重解析来源、不合并图层")
    @OperationLog(module = "GIS_VECTOR_STYLE", type = OperationType.UPDATE, description = "保存矢量图层挂载与显示配置")
    public ResponseEntity<ResponseResult<GisVectorVO.Layer>> presentation(@PathVariable Long layerId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody GisVectorRequests.Presentation request) {
        var layer = styles.savePresentation(layerId, ifMatch, request);
        return ResponseEntity.ok().eTag(VectorLayerService.layerEtag(layerId, layer.dataVersion(), layer.presentationVersion()))
                .body(ResponseResult.success(layer));
    }

    @PostMapping("/datasets/{dataSetId}/render-contexts")
    @Operation(summary = "创建数据集视窗渲染上下文", description = "bbox、连续displayZoom及CSS宽高必填；全图层共用样式与密度预算")
    public ResponseResult<GisVectorVO.RenderContext> renderContext(@PathVariable Long dataSetId,
            @Valid @RequestBody GisVectorRequests.RenderContext request) {
        return ResponseResult.success(tiles.createContext(dataSetId,request));
    }

    @GetMapping(value = "/datasets/{dataSetId}/tiles/{z}/{x}/{y}.mvt", produces = "application/vnd.mapbox-vector-tile")
    @Operation(summary = "按层级读取显示用MVT矢量瓦片", description = "须携带contextId；旧数据版本409，上下文过期410；不截取任意前N个要素")
    public ResponseEntity<byte[]> tile(@PathVariable Long dataSetId, @PathVariable int z, @PathVariable int x,
            @PathVariable int y, @RequestParam String contextId) {
        byte[] body = tiles.getTile(dataSetId,contextId,z,x,y);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(body);
    }

    @GetMapping("/datasets/{dataSetId}/clusters/{clusterId}/features")
    @Operation(summary = "分页查询视窗聚合成员属性", description = "返回图层ID和入库要素ID，不返回几何；编辑须另读单要素及ETag")
    public ResponseResult<GisVectorVO.ClusterMembers> clusterFeatures(@PathVariable Long dataSetId,
            @PathVariable String clusterId, @RequestParam String contextId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
        return ResponseResult.success(tiles.getClusterFeatures(dataSetId,contextId,clusterId,limit,cursor));
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
    @Operation(summary = "读取图层有效样式及ETag", description = "返回继承或自定义配置供面板展示；地图渲染须使用数据集视窗上下文和MVT")
    public ResponseEntity<ResponseResult<GisVectorVO.EffectiveStyle>> defaultStyle(@PathVariable Long layerId) {
        var style = styles.defaultStyle(layerId);
        return ResponseEntity.ok().eTag("\"effective-" + layerId + "-p" + style.presentationVersion() + "-s" + style.version() + "\"")
                .body(ResponseResult.success(style));
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
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long sourceFileId,
            @RequestParam(defaultValue = "1") int current, @RequestParam(defaultValue = "10") int size) {
        return ResponseResult.success(layers.page(dataSetId, groupId, sourceFileId, current, size));
    }

    @GetMapping("/layers/{layerId}")
    @Operation(summary = "查询图层详情", description = "返回ETag，删除图层时通过If-Match提交")
    public ResponseEntity<ResponseResult<GisVectorVO.Layer>> layer(@PathVariable Long layerId) {
        var layer = layers.detail(layerId);
        return ResponseEntity.ok().eTag(VectorLayerService.layerEtag(layerId, layer.dataVersion(), layer.presentationVersion())).body(ResponseResult.success(layer));
    }

    @DeleteMapping("/layers/{layerId}")
    @Operation(summary = "删除图层", description = "仅可删除已结束导入的图层；逻辑删除后要素不再可查询")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.DELETE, description = "删除矢量图层")
    public ResponseResult<Void> deleteLayer(@PathVariable Long layerId, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        layers.deleteLayer(layerId, ifMatch);
        return ResponseResult.success();
    }

    @GetMapping(value = "/layers/{layerId}/features", produces = "application/geo+json")
    @Operation(summary = "按视窗分页查询要素属性", description = "bbox必填；不返回几何，单要素编辑另读详情；禁止作为地图全量预览")
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
