package org.ocean.admin.gis.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** 已发布 GIS 瓦片的统一公开资源入口。 */
@RestController
@RequestMapping("/gis/publications")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class PublishedResourceController {

    private static final MediaType QUANTIZED_MESH =
            MediaType.parseMediaType("application/vnd.quantized-mesh");

    private final PublicationService publicationService;

    @GetMapping({"/{processingType}/{serviceCode}",
            "/{processingType}/{serviceCode}/",
            "/{processingType}/{serviceCode}/**"})
    public ResponseEntity<Resource> getPublishedResource(
            @PathVariable GisProcessingType processingType,
            @PathVariable String serviceCode,
            HttpServletRequest request) {
        try {
            String relativePath = extractRelativePath(
                    request, "/gis/publications/" + processingType.name()
                            + "/" + serviceCode);
            Path file = publicationService.resolvePublishedFile(
                    processingType, serviceCode, relativePath);
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            return ResponseEntity.ok()
                    .contentType(contentType(file, processingType))
                    .contentLength(size)
                    .lastModified(lastModified)
                    .eTag("\"" + size + "-" + lastModified + "\"")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePublic())
                    .body(new FileSystemResource(file));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "读取GIS发布资源失败", ex);
        }
    }

    private String extractRelativePath(HttpServletRequest request, String prefix) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (!requestPath.startsWith(prefix)) {
            throw new IllegalArgumentException("非法资源路径");
        }
        return UriUtils.decode(requestPath.substring(prefix.length()), StandardCharsets.UTF_8);
    }

    private MediaType contentType(Path file, GisProcessingType processingType) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (processingType == GisProcessingType.IMAGERY) {
            if (name.endsWith(".png")) {
                return MediaType.IMAGE_PNG;
            }
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                return MediaType.IMAGE_JPEG;
            }
        } else if (processingType == GisProcessingType.TERRAIN
                && name.endsWith(".terrain")) {
            return QUANTIZED_MESH;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
