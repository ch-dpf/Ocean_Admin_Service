package org.ocean.admin.gis.controller;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.ocean.admin.gis.service.FilePreviewService;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 内部数据预览接口，只接受本人会话；公开发布WMS继续使用原有入口。 */
@RestController
@RequestMapping("/api/gis/previews")
@RequiredArgsConstructor
public class FilePreviewController {
    private final FilePreviewService previews;

    @PostMapping("/groups/{groupId}")
    public ResponseEntity<?> create(@PathVariable Long groupId, HttpServletRequest request) {
        return ResponseEntity.accepted()
                .header("Cache-Control", "private, no-store")
                .body(ResponseResult.success(previews.create(groupId, owner(request))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(ResponseResult.success(previews.status(id, owner(request))));
    }

    @DeleteMapping("/{id}")
    public ResponseResult<?> close(@PathVariable String id, HttpServletRequest request) {
        previews.close(id, owner(request));
        return ResponseResult.success("预览已关闭");
    }

    @GetMapping("/{id}/features")
    public ResponseEntity<?> features(
            @PathVariable String id,
            @RequestParam String layerKey,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request)
            throws IOException {
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(
                        ResponseResult.success(
                                previews.features(id, owner(request), layerKey, after, limit)));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(
            @PathVariable String id,
            @RequestParam Map<String, String> parameters,
            HttpServletRequest request)
            throws IOException {
        var image =
                previews.image(
                        id,
                        owner(request),
                        options(parameters, required(parameters, "layerKey")),
                        null,
                        null,
                        integer(parameters, "width", 1600),
                        integer(parameters, "height", 1200));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .header("Cache-Control", "private, no-store")
                .body(image.bytes());
    }

    /** 内部WMS仅提供1.3.0，固定单个来源集合，仅返回PNG图像。 */
    @GetMapping("/{id}/wms")
    public ResponseEntity<?> wms(
            @PathVariable String id,
            @RequestParam Map<String, String> input,
            HttpServletRequest request)
            throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        input.forEach((key, value) -> parameters.put(key.toUpperCase(Locale.ROOT), value));
        Long owner = owner(request);
        try {
            if (!"WMS".equalsIgnoreCase(required(parameters, "SERVICE")))
                throw new IllegalArgumentException("SERVICE须为WMS");
            if (!"1.3.0".equals(parameters.getOrDefault("VERSION", "1.3.0")))
                throw new IllegalArgumentException("预览仅支持WMS 1.3.0");
            String operation = required(parameters, "REQUEST").toUpperCase(Locale.ROOT);
            if (operation.equals("GETCAPABILITIES"))
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header("Cache-Control", "private, no-store")
                        .body(
                                capabilities(
                                        previews.status(id, owner),
                                        request.getRequestURL().toString()));
            if (!Set.of("GETMAP").contains(operation))
                throw new IllegalArgumentException("不支持该WMS操作");
            String layer = required(parameters, "LAYERS");
            if (layer.contains(",")) throw new IllegalArgumentException("每个请求只支持一个来源集合");
            String crs = required(parameters, "CRS").toUpperCase(Locale.ROOT);
            if (!Set.of("EPSG:4326", "EPSG:3857", "CRS:84").contains(crs))
                throw new IllegalArgumentException("坐标系不受支持");
            double[] bbox = bounds(required(parameters, "BBOX"));
            if (crs.equals("EPSG:4326")) bbox = new double[] {bbox[1], bbox[0], bbox[3], bbox[2]};
            int width = integer(parameters, "WIDTH", 0), height = integer(parameters, "HEIGHT", 0);
            if (width < 1 || height < 1 || width > 2048 || height > 2048)
                throw new IllegalArgumentException("地图尺寸须为1至2048");
            if (!"image/png".equals(parameters.getOrDefault("FORMAT", "image/png")))
                throw new IllegalArgumentException("仅支持image/png");
            String style = parameters.getOrDefault("STYLES", "");
            if (!style.isBlank()) throw new IllegalArgumentException("文件预览仅支持原始默认样式");
            var options = options(Map.of("band", parameters.getOrDefault("BAND", "")), layer);
            var image = previews.image(id, owner, options, bbox, crs, width, height);
            byte[] body = image.bytes();
            if (!"TRUE".equalsIgnoreCase(parameters.getOrDefault("TRANSPARENT", "FALSE"))) {
                String background = parameters.getOrDefault("BGCOLOR", "0xFFFFFF");
                if (!background.matches("0[xX][0-9a-fA-F]{6}"))
                    throw new IllegalArgumentException("背景色无效");
                var source = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(body));
                var opaque =
                        new java.awt.image.BufferedImage(
                                width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = opaque.createGraphics();
                try {
                    graphics.setColor(java.awt.Color.decode(background));
                    graphics.fillRect(0, 0, width, height);
                    graphics.drawImage(source, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                var output = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(opaque, "PNG", output);
                body = output.toByteArray();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "private, no-store")
                    .body(body);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_XML)
                    .header("Cache-Control", "private, no-store")
                    .body(
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ServiceExceptionReport"
                                    + " version=\"1.3.0\""
                                    + " xmlns=\"http://www.opengis.net/ogc\"><ServiceException"
                                    + " code=\"InvalidParameterValue\">"
                                    + xml(ex.getMessage())
                                    + "</ServiceException></ServiceExceptionReport>");
        }
    }

    private String capabilities(JsonNode session, String url) {
        if (!"READY".equals(session.path("status").asText()))
            throw new IllegalArgumentException("预览尚未就绪");
        String online =
                "<OnlineResource xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:type=\"simple\""
                        + " xlink:href=\""
                        + xml(url)
                        + "?\"/>";
        StringBuilder result =
                new StringBuilder(
                                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><WMS_Capabilities"
                                    + " version=\"1.3.0\""
                                    + " xmlns=\"http://www.opengis.net/wms\"><Service><Name>WMS</Name><Title>原始数据预览</Title>")
                        .append(online)
                        .append("</Service><Capability><Request>");
        for (String operation : List.of("GetCapabilities", "GetMap")) {
            String format =
                    operation.equals("GetMap")
                            ? "image/png"
                            : operation.equals("GetCapabilities") ? "text/xml" : "application/json";
            result.append("<")
                    .append(operation)
                    .append("><Format>")
                    .append(format)
                    .append("</Format><DCPType><HTTP><Get>")
                    .append(online)
                    .append("</Get></HTTP></DCPType></")
                    .append(operation)
                    .append(">");
        }
        result.append(
                "</Request><Exception><Format>XML</Format></Exception><Layer><Title>原始数据</Title><CRS>EPSG:3857</CRS><CRS>EPSG:4326</CRS><CRS>CRS:84</CRS>");
        if ("RASTER".equals(session.path("descriptor").path("kind").asText())) {
            for (JsonNode layer : session.path("descriptor").path("layers")) {
                if (!layer.path("mapAvailable").asBoolean()) continue;
                JsonNode b = layer.path("bounds");
                result.append("<Layer queryable=\"0\"><Name>")
                        .append(xml(layer.path("key").asText()))
                        .append("</Name><Title>")
                        .append(xml(session.path("name").asText()))
                        .append("</Title><EX_GeographicBoundingBox><westBoundLongitude>")
                        .append(b.get(0).asDouble())
                        .append("</westBoundLongitude><eastBoundLongitude>")
                        .append(b.get(2).asDouble())
                        .append("</eastBoundLongitude><southBoundLatitude>")
                        .append(b.get(1).asDouble())
                        .append("</southBoundLatitude><northBoundLatitude>")
                        .append(b.get(3).asDouble())
                        .append("</northBoundLatitude></EX_GeographicBoundingBox>")
                        .append("</Layer>");
            }
        }
        return result.append("</Layer></Capability></WMS_Capabilities>").toString();
    }

    private static FilePreviewService.Request options(
            Map<String, String> parameters, String layer) {
        String band = parameters.get("band");
        try {
            return new FilePreviewService.Request(
                    layer, band == null || band.isBlank() ? null : Integer.valueOf(band));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("内容编号无效");
        }
    }

    private static double[] bounds(String value) {
        try {
            double[] result =
                    Arrays.stream(value.split(",", -1)).mapToDouble(Double::parseDouble).toArray();
            if (result.length != 4
                    || Arrays.stream(result).anyMatch(v -> !Double.isFinite(v))
                    || result[0] >= result[2]
                    || result[1] >= result[3]) throw new IllegalArgumentException("BBOX范围无效");
            return result;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("BBOX格式无效");
        }
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        try {
            return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + "须为整数");
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数" + key);
        return value;
    }

    private static Long owner(HttpServletRequest request) {
        Object operator = request.getAttribute(CurrentOperator.REQUEST_ATTRIBUTE);
        if (operator instanceof CurrentOperator current) return current.userId();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }

    private static String xml(String value) {
        return value == null
                ? ""
                : value.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                        .replace("'", "&apos;");
    }
}
