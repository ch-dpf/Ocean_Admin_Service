package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.GisVectorRequests;
import org.ocean.admin.gis.service.ProcessingService;
import org.ocean.admin.gis.service.VectorTileService;
import org.ocean.admin.gis.vo.GisVectorVO;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


/** 矢量地图预览与静态成果提交接口。 */
@RestController
@RequestMapping("/api/gis/vector")
@RequiredArgsConstructor
@Tag(name = "GIS矢量预览")
public class VectorPreviewController {
    private final VectorTileService tiles;
    private final ProcessingService processing;

    @PostMapping("/datasets/{dataSetId}/static-tiles")
    @Operation(summary = "使用已保存样式方案生成静态矢量瓦片")
    public ResponseResult<?> buildStaticTiles(@PathVariable Long dataSetId,
            @Valid @RequestBody GisVectorRequests.TileBuild request) {
        return ResponseResult.success(processing.submitVector(dataSetId, request, "MVT"));
    }

    @PostMapping("/datasets/{dataSetId}/static-raster-tiles")
    @Operation(summary = "使用已保存样式方案生成静态栅格瓦片")
    public ResponseResult<?> buildStaticRasterTiles(@PathVariable Long dataSetId,
            @Valid @RequestBody GisVectorRequests.TileBuild request) {
        return ResponseResult.success(processing.submitVector(dataSetId, request, "PNG"));
    }

    @GetMapping("/datasets/{dataSetId}/map-definition")
    @Operation(summary = "查询稳定的XYZ瓦片数据契约")
    public ResponseEntity<ResponseResult<GisVectorVO.MapDefinition>> mapDefinition(@PathVariable Long dataSetId) {
        var definition = tiles.definition(dataSetId);
        return ResponseEntity.ok().header("Cache-Control", "private, no-cache")
                .eTag("\"map-" + definition.tileVersion() + "-" + definition.changeCursor() + "\"").body(ResponseResult.success(definition));
    }

    @GetMapping(value = "/datasets/{dataSetId}/tiles/{tileVersion}/{z}/{x}/{y}.mvt", produces = "application/vnd.mapbox-vector-tile")
    @Operation(summary = "按数据版本和XYZ读取几何瓦片", description = "不含配图，不依赖视窗；旧版本缓存未命中返回410")
    public ResponseEntity<byte[]> tile(@PathVariable Long dataSetId, @PathVariable String tileVersion,
            @PathVariable int z, @PathVariable int x, @PathVariable int y,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        byte[] body = tiles.getTile(dataSetId,tileVersion,z,x,y);
        // 同一MVT可由HTTP层协商为gzip或identity；强ETag会阻止Tomcat压缩。
        String tag = "\"" + org.ocean.admin.gis.service.VectorRenderPlanner.digest(body) + "\"";
        String etag = "W/" + tag;
        boolean unchanged = ifNoneMatch != null && java.util.Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim).anyMatch(value -> value.equals("*") || value.equals(tag) || value.equals(etag));
        return ResponseEntity.status(unchanged ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header("Cache-Control","private, no-cache")
                .header("Vary","Accept-Encoding").eTag(etag)
                .body(unchanged ? null : body);
    }

    @GetMapping("/datasets/{dataSetId}/tile-changes")
    @Operation(summary = "补取要素修改影响的瓦片范围")
    public ResponseEntity<ResponseResult<GisVectorVO.TileChanges>> tileChanges(@PathVariable Long dataSetId,
            @RequestParam(defaultValue = "0") long after) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(ResponseResult.success(tiles.changes(dataSetId, after)));
    }
}
