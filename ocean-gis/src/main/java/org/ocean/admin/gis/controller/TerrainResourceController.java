package org.ocean.admin.gis.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.TerrainPublicationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 无需业务鉴权的 Cesium 地形数据面，仅暴露已经发布的受控目录。 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class TerrainResourceController {

    private static final MediaType QUANTIZED_MESH =
            MediaType.parseMediaType("application/vnd.quantized-mesh");

    private final TerrainPublicationService terrainPublicationService;

    @GetMapping({"/terrain/{serviceCode}", "/terrain/{serviceCode}/",
            "/terrain/{serviceCode}/**"})
    public ResponseEntity<Resource> getResource(
            @PathVariable String serviceCode,
            HttpServletRequest request) {
        try {
            String relativePath = extractRelativePath(request, serviceCode);
            Path file = terrainPublicationService.resolvePublishedFile(serviceCode, relativePath);
            return ResponseEntity.ok()
                    .contentType(contentType(file))
                    .contentLength(Files.size(file))
                    .lastModified(Files.getLastModifiedTime(file).toMillis())
                    .eTag(etag(file))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic())
                    .body(new FileSystemResource(file));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "读取地形资源失败", ex);
        }
    }

    private String extractRelativePath(HttpServletRequest request, String serviceCode) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = "/terrain/" + serviceCode;
        if (!requestPath.startsWith(prefix)) {
            throw new IllegalArgumentException("非法地形资源路径");
        }
        String encoded = requestPath.substring(prefix.length());
        return UriUtils.decode(encoded, StandardCharsets.UTF_8);
    }

    private MediaType contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (name.endsWith(".terrain")) {
            return QUANTIZED_MESH;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String etag(Path file) throws IOException {
        return "\"" + Files.size(file) + "-"
                + Files.getLastModifiedTime(file).toMillis() + "\"";
    }
}
