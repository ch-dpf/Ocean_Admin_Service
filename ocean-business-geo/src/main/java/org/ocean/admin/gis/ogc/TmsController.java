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
 * TMS 1.0.0 公开服务入口（OSGeo Tile Map Service Specification）。
 *
 * <p>地址结构与 GeoServer 内置 TMS 服务一致：</p>
 * <pre>
 * /gis/tms/1.0.0                                                       TileMapService
 * /gis/tms/1.0.0/{服务编码}@{矩阵集}@{格式}                              TileMap
 * /gis/tms/1.0.0/{服务编码}@{矩阵集}@{格式}/{z}/{x}/{y}.{格式}           瓦片
 * </pre>
 *
 * <p>与其它 OGC 入口一致，不经过 {@code /api} 鉴权拦截。TMS 1.0.0 未定义异常报文，
 * 失败时直接返回 HTTP 状态码与纯文本说明。</p>
 */
@Slf4j
@RestController
@RequestMapping("/gis/tms")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TmsController {

    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");
    private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final String VERSION_PATH = "/1.0.0";

    private final TmsService tmsService;

    public TmsController(TmsService tmsService) {
        this.tmsService = tmsService;
    }

    /** 服务根文档：列出全部已发布图层。 */
    @GetMapping(VERSION_PATH)
    public ResponseEntity<?> tileMapService() {
        try {
            return document(tmsService.tileMapServiceXml());
        } catch (RuntimeException ex) {
            return failure(ex);
        }
    }

    /** 单个图层的 TileMap 文档。 */
    @GetMapping(VERSION_PATH + "/{spec}")
    public ResponseEntity<?> tileMap(@PathVariable String spec) {
        try {
            return document(tmsService.tileMapXml(spec));
        } catch (RuntimeException ex) {
            return failure(ex);
        }
    }

    /**
     * 取瓦片。
     *
     * @param yWithExtension 形如 {@code 6789.png} 的行号与格式扩展名
     */
    @GetMapping(VERSION_PATH + "/{spec}/{zoom}/{column}/{yWithExtension}")
    public ResponseEntity<?> tile(@PathVariable String spec,
            @PathVariable String zoom,
            @PathVariable String column,
            @PathVariable String yWithExtension) {
        try {
            return tileReply(tmsService.resolveTile(
                    spec, zoom, column, stripExtension(yWithExtension)));
        } catch (RuntimeException ex) {
            return failure(ex);
        }
    }

    /** 地址里的行号带格式扩展名；扩展名本身已由 TileMap 标识确定，这里只取行号。 */
    private String stripExtension(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.lastIndexOf('.');
        return separator <= 0 ? value : value.substring(0, separator);
    }

    private ResponseEntity<String> document(String xml) {
        return ResponseEntity.ok().contentType(XML)
                .header("Cache-Control", "no-store").body(xml);
    }

    private ResponseEntity<?> tileReply(TmsService.TileResource tile) {
        if (tile.body() != null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(tile.mediaType()))
                    .contentLength(tile.body().length)
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                    .body(tile.body());
        }
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
            log.error("[OGC][TMS] 读取瓦片失败: {}", file, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(TEXT).body("读取瓦片失败");
        }
    }

    private ResponseEntity<String> failure(RuntimeException ex) {
        if (ex instanceof TmsService.TmsException tms) {
            return ResponseEntity.status(tms.status())
                    .contentType(TEXT)
                    .header("Cache-Control", "no-store")
                    .body(tms.getMessage());
        }
        log.error("[OGC][TMS] 请求处理失败", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(TEXT).body("服务内部错误");
    }
}
