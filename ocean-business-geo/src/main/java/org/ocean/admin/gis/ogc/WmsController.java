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
 * WMS 1.3.0 公开服务入口（KVP 编码）。
 *
 * <p>与既有的 {@code /gis/publications/**} 与 {@code /gis/wmts} 一致，
 * 不经过 {@code /api} 鉴权拦截，供 GIS 客户端直接接入。</p>
 */
@Slf4j
@RestController
@RequestMapping("/gis/wms")
@CrossOrigin(origins = "*", maxAge = 3600)
public class WmsController {

    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");

    private final WmsService wmsService;

    public WmsController(WmsService wmsService) {
        this.wmsService = wmsService;
    }

    @GetMapping
    public ResponseEntity<?> kvp(HttpServletRequest request) {
        try {
            return respond(wmsService.handleKvp(parameters(request)));
        } catch (WmsService.OgcException ex) {
            return exception(ex);
        } catch (RuntimeException ex) {
            log.error("[OGC][WMS] 请求处理失败", ex);
            return exception(new WmsService.OgcException(
                    "NoApplicableCode", null, "服务内部错误"));
        }
    }

    private ResponseEntity<?> respond(WmsService.WmsReply reply) {
        // 动态出图与像元查询结果随数据变化，不做强缓存
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(reply.mediaType()))
                .cacheControl(CacheControl.noStore())
                .body(reply.body());
    }

    private ResponseEntity<String> exception(WmsService.OgcException ex) {
        return ResponseEntity
                .status(ex.serverError() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST)
                .contentType(XML)
                .header("Cache-Control", "no-store")
                .body(wmsService.exceptionXml(ex));
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
