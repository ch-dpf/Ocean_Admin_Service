package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 量化地形（quantized-mesh）公开服务入口，CesiumTerrainProvider 兼容。
 *
 * <p>每个服务编码独占一套元数据与瓦片地址：</p>
 * <pre>
 * /gis/terrain/{服务编码}/layer.json            量化地形元数据（原样透传切片产物）
 * /gis/terrain/{服务编码}/{z}/{x}/{y}.terrain   瓦片
 * </pre>
 *
 * <p>与其它 OGC 入口一致，不经过 {@code /api} 鉴权拦截。量化地形没有标准异常报文，
 * 失败时直接返回 HTTP 状态码与纯文本说明。</p>
 */
@Slf4j
@RestController
@RequestMapping("/gis/terrain")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TerrainController {

    private static final MediaType JSON = MediaType.parseMediaType("application/json;charset=UTF-8");
    private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final TerrainService terrainService;

    public TerrainController(TerrainService terrainService) {
        this.terrainService = terrainService;
    }

    /** 量化地形元数据：原样返回切片产物的 layer.json，保留 bounds、available 与扩展声明。 */
    @GetMapping("/{serviceCode}/layer.json")
    public ResponseEntity<?> layerJson(@PathVariable String serviceCode) {
        try {
            Path file = terrainService.resolveLayerJson(serviceCode);
            return ResponseEntity.ok().contentType(JSON)
                    .header("Cache-Control", "no-store")
                    .body(new FileSystemResource(file));
        } catch (RuntimeException ex) {
            return failure(ex);
        }
    }

    /**
     * 取量化地形瓦片。
     *
     * @param yWithExtension 形如 {@code 6789.terrain} 的行号与扩展名
     */
    @GetMapping("/{serviceCode}/{zoom}/{column}/{yWithExtension}")
    public ResponseEntity<?> tile(@PathVariable String serviceCode,
            @PathVariable String zoom,
            @PathVariable String column,
            @PathVariable String yWithExtension) {
        try {
            return tileReply(terrainService.resolveTile(
                    serviceCode, zoom, column, stripExtension(yWithExtension)));
        } catch (RuntimeException ex) {
            return failure(ex);
        }
    }

    /** 地址里的行号带格式扩展名；扩展名由 layer.json 的 format 确定，这里只取行号。 */
    private String stripExtension(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.lastIndexOf('.');
        return separator <= 0 ? value : value.substring(0, separator);
    }

    private ResponseEntity<?> tileReply(TerrainService.TileResource tile) {
        Path file = tile.file();
        try {
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(tile.mediaType()))
                    .contentLength(size)
                    .lastModified(lastModified)
                    .eTag("\"" + size + "-" + lastModified + "\"")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic())
                    .body(new FileSystemResource(file));
        } catch (IOException ex) {
            log.error("[OGC][TERRAIN] 读取瓦片失败: {}", file, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(TEXT).body("读取瓦片失败");
        }
    }

    private ResponseEntity<String> failure(RuntimeException ex) {
        if (ex instanceof TerrainService.TerrainException terrain) {
            return ResponseEntity.status(terrain.status())
                    .contentType(TEXT)
                    .header("Cache-Control", "no-store")
                    .body(terrain.getMessage());
        }
        log.error("[OGC][TERRAIN] 请求处理失败", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(TEXT).body("服务内部错误");
    }
}
