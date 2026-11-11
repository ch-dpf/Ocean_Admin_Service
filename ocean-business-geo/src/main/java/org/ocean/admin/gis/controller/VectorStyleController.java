package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.service.VectorLayerService;
import org.ocean.admin.gis.service.VectorStyleService;
import org.ocean.admin.gis.service.VectorStyleSchemeService;
import org.ocean.admin.gis.service.VectorCartographyService;
import org.ocean.admin.gis.service.VectorDataSetStyleService;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** 矢量分类、图层样式与制图配置接口。 */
@RestController
@RequestMapping("/api/gis/vector")
@RequiredArgsConstructor
@Tag(name = "GIS矢量制图")
public class VectorStyleController {
    private final VectorStyleService styles;
    private final VectorStyleSchemeService schemes;
    private final VectorCartographyService cartography;
    private final VectorDataSetStyleService dataSetStyles;

    @GetMapping("/datasets/{dataSetId}/style-schemes")
    public ResponseResult<?> schemes(@PathVariable Long dataSetId, @RequestParam(defaultValue = "false") boolean summary) {
        return ResponseResult.success(summary ? schemes.schemeOptions(dataSetId) : schemes.schemes(dataSetId));
    }

    @GetMapping("/datasets/{dataSetId}/style-schemes/{id}")
    public ResponseEntity<?> scheme(@PathVariable Long dataSetId,@PathVariable Long id) {
        var value=schemes.scheme(dataSetId,id);
        return ResponseEntity.ok().eTag(VectorStyleSchemeService.schemeEtag(id,value.version())).body(ResponseResult.success(value));
    }

    @PostMapping("/datasets/{dataSetId}/style-schemes")
    @Operation(summary="创建独立样式方案；省略definition时复制当前制图配置")
    public ResponseResult<?> createScheme(@PathVariable Long dataSetId,@Valid @RequestBody GisVectorRequests.Scheme request) {
        return ResponseResult.success(schemes.saveScheme(dataSetId,null,null,request));
    }

    @PostMapping("/datasets/{dataSetId}/style-schemes/import")
    @Operation(summary="从外部分类样式JSON创建独立方案")
    public ResponseResult<?> importScheme(@PathVariable Long dataSetId, @Valid @RequestBody GisVectorRequests.Style request) {
        return ResponseResult.success(schemes.importScheme(dataSetId, request));
    }

    @PutMapping("/datasets/{dataSetId}/style-schemes/{id}")
    public ResponseResult<?> updateScheme(@PathVariable Long dataSetId,@PathVariable Long id,
            @RequestHeader(value="If-Match",required=false) String ifMatch,@Valid @RequestBody GisVectorRequests.Scheme request) {
        return ResponseResult.success(schemes.saveScheme(dataSetId,id,ifMatch,request));
    }

    @DeleteMapping("/datasets/{dataSetId}/style-schemes/{id}")
    public ResponseResult<?> deleteScheme(@PathVariable Long dataSetId,@PathVariable Long id,
            @RequestHeader(value="If-Match",required=false) String ifMatch) {
        schemes.deleteScheme(dataSetId,id,ifMatch);return ResponseResult.success("样式方案已删除，已发布快照保持不变");
    }

    @GetMapping("/datasets/{dataSetId}/style")
    @Operation(summary = "查询数据集样式快照与默认规则", description = "仅配置，不含要素几何")
    public ResponseResult<GisVectorVO.DataSetStyle> dataSetStyle(@PathVariable Long dataSetId) {
        return ResponseResult.success(dataSetStyles.detail(dataSetId));
    }

    @GetMapping("/datasets/{dataSetId}/groups")
    @Operation(summary = "查询数据集分类树与统计", description = "图层通过分页接口按分类加载，不内嵌几何")
    public ResponseEntity<ResponseResult<List<GisVectorVO.Group>>> groups(@PathVariable Long dataSetId) {
        var tree = dataSetStyles.groupTree(dataSetId);
        return ResponseEntity.ok().header("Cache-Control","private, no-cache").eTag(tree.etag()).body(ResponseResult.success(tree.groups()));
    }

    @PostMapping("/datasets/{dataSetId}/basic-styles")
    @Operation(summary = "为未配置的未分类图层批量应用基础样式", description = "If-Match来自分类树；保留人工配置与已保存方案")
    @OperationLog(module = "GIS_VECTOR", type = OperationType.UPDATE, description = "批量应用矢量基础样式")
    public ResponseResult<Integer> applyBasicStyles(@PathVariable Long dataSetId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ResponseResult.success(styles.applyBasicStyles(dataSetId, ifMatch));
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

    @GetMapping("/datasets/{dataSetId}/cartography")
    public ResponseEntity<ResponseResult<GisVectorVO.Cartography>> cartography(@PathVariable Long dataSetId) {
        var config = cartography.cartography(dataSetId);
        return ResponseEntity.ok().header("Cache-Control","private, no-cache")
                .eTag(VectorCartographyService.cartographyEtag(dataSetId,config.styleVersion())).body(ResponseResult.success(config));
    }

    @PutMapping("/datasets/{dataSetId}/groups/{groupId}/cartography")
    public ResponseResult<Void> groupCartography(@PathVariable Long dataSetId, @PathVariable Long groupId,
            @RequestHeader(value="If-Match",required=false) String ifMatch, @RequestBody JsonNode patch) {
        cartography.saveCartography(dataSetId,groupId,"GROUP",patch,ifMatch);
        return ResponseResult.success();
    }

    @PostMapping("/datasets/{dataSetId}/feature-cartography/query")
    public ResponseEntity<ResponseResult<JsonNode>> featureCartography(@PathVariable Long dataSetId,
            @Valid @RequestBody GisVectorRequests.FeatureCartographyQuery query) {
        return ResponseEntity.ok().header("Cache-Control","private, no-cache")
                .body(ResponseResult.success(cartography.featureCartography(dataSetId,query.featureIds())));
    }

    @PutMapping("/datasets/{dataSetId}/features/{featureId}/cartography")
    public ResponseResult<Void> featureCartography(@PathVariable Long dataSetId, @PathVariable Long featureId,
            @RequestHeader(value="If-Match",required=false) String ifMatch, @RequestBody JsonNode patch) {
        cartography.saveCartography(dataSetId,featureId,"FEATURE",patch,ifMatch);
        return ResponseResult.success();
    }

    @DeleteMapping("/datasets/{dataSetId}/features/{featureId}/cartography")
    public ResponseResult<Void> deleteFeatureCartography(@PathVariable Long dataSetId, @PathVariable Long featureId,
            @RequestHeader(value="If-Match",required=false) String ifMatch) {
        cartography.deleteFeatureCartography(dataSetId,featureId,ifMatch);
        return ResponseResult.success();
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
    @Operation(summary = "读取图层有效样式及ETag", description = "返回继承或自定义配置供面板展示；地图渲染使用独立配图及版本化MVT")
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
}
