package org.ocean.admin.gis.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.ImageryPublicationService;
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

/** 无需业务鉴权的影像瓦片数据面，仅暴露已发布的 TileJSON 和 XYZ 瓦片。 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class ImageryResourceController {

    private final ImageryPublicationService imageryPublicationService;

    @GetMapping({"/imagery/{serviceCode}", "/imagery/{serviceCode}/",
            "/imagery/{serviceCode}/**"})
    public ResponseEntity<Resource> getResource(
            @PathVariable String serviceCode,
            HttpServletRequest request) {
        try {
            String relativePath = extractRelativePath(request, serviceCode);
            Path file = imageryPublicationService.resolvePublishedFile(serviceCode, relativePath);
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
                    "读取影像资源失败", ex);
        }
    }

    private String extractRelativePath(HttpServletRequest request, String serviceCode) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = "/imagery/" + serviceCode;
        if (!requestPath.startsWith(prefix)) {
            throw new IllegalArgumentException("非法影像资源路径");
        }
        return UriUtils.decode(requestPath.substring(prefix.length()), StandardCharsets.UTF_8);
    }

    private MediaType contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String etag(Path file) throws IOException {
        return "\"" + Files.size(file) + "-"
                + Files.getLastModifiedTime(file).toMillis() + "\"";
    }
}
