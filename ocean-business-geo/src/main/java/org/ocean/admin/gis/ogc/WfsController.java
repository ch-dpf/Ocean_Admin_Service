package org.ocean.admin.gis.ogc;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WFS 2.0.0 公开服务入口（KVP 编码），仅提供只读要素查询。
 *
 * <p>与既有 OGC 入口一致，不经过 {@code /api} 鉴权拦截，供 QGIS 等标准客户端
 * 直接接入；异常统一转换为 OWS ExceptionReport。</p>
 */
@Slf4j
@RestController
@RequestMapping("/gis/wfs")
@CrossOrigin(origins = "*", maxAge = 3600)
public class WfsController {

    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");

    private final WfsService wfsService;

    public WfsController(WfsService wfsService) {
        this.wfsService = wfsService;
    }

    @GetMapping
    public ResponseEntity<?> kvp(HttpServletRequest request) {
        try {
            WfsService.WfsReply reply = wfsService.handleKvp(parameters(request));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(reply.mediaType()))
                    .cacheControl(CacheControl.noStore())
                    .body(reply.body());
        } catch (WmtsService.OgcException ex) {
            return exception(ex);
        } catch (RuntimeException ex) {
            log.error("[OGC][WFS] 请求处理失败", ex);
            return exception(new WmtsService.OgcException(
                    "NoApplicableCode", null, "服务内部错误"));
        }
    }

    private ResponseEntity<String> exception(WmtsService.OgcException ex) {
        return ResponseEntity
                .status(ex.serverError() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST)
                .contentType(XML)
                .header("Cache-Control", "no-store")
                .body(wfsService.exceptionXml(ex));
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
