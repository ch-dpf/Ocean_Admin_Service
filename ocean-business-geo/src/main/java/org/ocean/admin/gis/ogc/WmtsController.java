package org.ocean.admin.gis.ogc;

import jakarta.servlet.http.HttpServletRequest;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WMTS 1.0.0 公开服务入口，同时支持 KVP 与 RESTful 两种取图方式。
 *
 * <p>与既有的 {@code /gis/publications/**} 一致，不经过 {@code /api} 鉴权拦截。</p>
 */
@Slf4j
@RestController
@RequestMapping("/gis/wmts")
@CrossOrigin(origins = "*", maxAge = 3600)
public class WmtsController {

    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");

    private final WmtsService wmtsService;

    public WmtsController(WmtsService wmtsService) {
        this.wmtsService = wmtsService;
    }

    @GetMapping
    public ResponseEntity<?> kvp(HttpServletRequest request) {
        try {
            return respond(wmtsService.handleKvp(parameters(request)));
        } catch (WmtsService.OgcException ex) {
            return exception(ex);
        } catch (RuntimeException ex) {
            log.error("[OGC][WMTS] KVP 请求处理失败", ex);
            return exception(new WmtsService.OgcException(
                    "NoApplicableCode", null, "服务内部错误"));
        }
    }

    @GetMapping("/rest/{layer}/{style}/{tileMatrixSet}/{tileMatrix}/{tileRow}/{tileColWithExtension}")
    public ResponseEntity<?> rest(
            @PathVariable String layer,
            @PathVariable String style,
            @PathVariable String tileMatrixSet,
            @PathVariable String tileMatrix,
            @PathVariable String tileRow,
            @PathVariable String tileColWithExtension) {
        try {
            return respond(wmtsService.handleRestTile(
                    layer, style, tileMatrixSet, tileMatrix, tileRow, tileColWithExtension));
        } catch (WmtsService.OgcException ex) {
            return exception(ex);
        } catch (RuntimeException ex) {
            log.error("[OGC][WMTS] RESTful 请求处理失败", ex);
            return exception(new WmtsService.OgcException(
                    "NoApplicableCode", null, "服务内部错误"));
        }
    }

    private ResponseEntity<?> respond(WmtsService.WmtsReply reply) {
        if (reply.xml() != null) {
            return ResponseEntity.ok().contentType(XML)
                    .header("Cache-Control", "no-store").body(reply.xml());
        }
        Path file = reply.file();
        try {
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(reply.mediaType()))
                    .contentLength(size)
                    .lastModified(lastModified)
                    .eTag("\"" + size + "-" + lastModified + "\"")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic())
                    .body(new FileSystemResource(file));
        } catch (IOException ex) {
            log.error("[OGC][WMTS] 读取瓦片失败: {}", file, ex);
            throw new WmtsService.OgcException("NoApplicableCode", null, "读取瓦片失败");
        }
    }

    private ResponseEntity<String> exception(WmtsService.OgcException ex) {
        return ResponseEntity
                .status(ex.serverError() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST)
                .contentType(XML)
                .header("Cache-Control", "no-store")
                .body(wmtsService.exceptionXml(ex));
    }

    private Map<String, String> parameters(HttpServletRequest request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                parameters.put(name, values[0]);
            }
        });
        return parameters;
    }
}
