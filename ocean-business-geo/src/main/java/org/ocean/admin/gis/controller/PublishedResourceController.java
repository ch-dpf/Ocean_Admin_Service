package org.ocean.admin.gis.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.ImageryPublicationService;
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
import java.util.Locale;
import java.util.function.BiFunction;

/** 已发布影像和地形瓦片的公开资源入口。 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class PublishedResourceController {

    private static final MediaType QUANTIZED_MESH =
            MediaType.parseMediaType("application/vnd.quantized-mesh");

    private final ImageryPublicationService imageryPublicationService;
    private final TerrainPublicationService terrainPublicationService;

    @GetMapping({"/imagery/{serviceCode}", "/imagery/{serviceCode}/",
            "/imagery/{serviceCode}/**"})
    public ResponseEntity<Resource> getImageryResource(
            @PathVariable String serviceCode, HttpServletRequest request) {
        return serveResource(serviceCode, request, "/imagery/", "影像", true,
                imageryPublicationService::resolvePublishedFile);
    }

    @GetMapping({"/terrain/{serviceCode}", "/terrain/{serviceCode}/",
            "/terrain/{serviceCode}/**"})
    public ResponseEntity<Resource> getTerrainResource(
            @PathVariable String serviceCode, HttpServletRequest request) {
        return serveResource(serviceCode, request, "/terrain/", "地形", false,
                terrainPublicationService::resolvePublishedFile);
    }

    private ResponseEntity<Resource> serveResource(
            String serviceCode,
            HttpServletRequest request,
            String basePath,
            String resourceType,
            boolean imagery,
            BiFunction<String, String, Path> resolveFile) {
        try {
            String relativePath = extractRelativePath(request, basePath + serviceCode);
            Path file = resolveFile.apply(serviceCode, relativePath);
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            return ResponseEntity.ok()
                    .contentType(contentType(file, imagery))
                    .contentLength(size)
                    .lastModified(lastModified)
                    .eTag("\"" + size + "-" + lastModified + "\"")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic())
                    .body(new FileSystemResource(file));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "读取" + resourceType + "资源失败", ex);
        }
    }

    private String extractRelativePath(HttpServletRequest request, String prefix) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (!requestPath.startsWith(prefix)) {
            throw new IllegalArgumentException("非法资源路径");
        }
        return UriUtils.decode(requestPath.substring(prefix.length()), StandardCharsets.UTF_8);
    }

    private MediaType contentType(Path file, boolean imagery) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (imagery) {
            if (name.endsWith(".png")) {
                return MediaType.IMAGE_PNG;
            }
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                return MediaType.IMAGE_JPEG;
            }
        } else if (name.endsWith(".terrain")) {
            return QUANTIZED_MESH;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
