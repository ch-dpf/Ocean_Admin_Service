package org.ocean.admin.gis.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.service.PublicationService;
import org.ocean.admin.gis.service.VectorRasterPublicationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;
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
    private static final MediaType XML_UTF8 = new MediaType("text", "xml", StandardCharsets.UTF_8);

    private final PublicationService publicationService;
    private final org.ocean.admin.gis.service.VectorPublicationService vectors;
    private final VectorRasterPublicationService raster;
    private final org.ocean.admin.gis.service.VectorWmsPublicationService wms;

    @GetMapping("/VECTOR/{serviceCode}/wms")
    public ResponseEntity<?> wms(@PathVariable String serviceCode, @RequestParam MultiValueMap<String, String> parameters) {
        var result = wms.handle(serviceCode, parameters);
        if (result.xml() != null) return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(result.contentType() + ";charset=UTF-8")).body(result.xml());
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.IMAGE_PNG)
                .eTag("\"" + org.ocean.admin.gis.service.VectorRenderPlanner.digest(result.image()) + "\"").body(result.image());
    }

    @ExceptionHandler(org.ocean.admin.gis.service.VectorWmsPublicationService.WmsException.class)
    public ResponseEntity<String> wmsError(org.ocean.admin.gis.service.VectorWmsPublicationService.WmsException error) {
        String contentType = "1.1.1".equals(error.version) ? "application/vnd.ogc.se_xml;charset=UTF-8" : "text/xml;charset=UTF-8";
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(contentType))
                .body(org.ocean.admin.gis.service.VectorWmsPublicationService.exceptionXml(error));
    }

    @GetMapping("/VECTOR/{serviceCode}/wmts")
    public ResponseEntity<?> wmts(@PathVariable String serviceCode,
            @RequestParam MultiValueMap<String, String> parameters) throws IOException {
        var result = raster.wmts(serviceCode, parameters);
        return result.xml() != null ? xml(result.xml()) : rasterTile(result.tile());
    }

    @GetMapping("/VECTOR/{serviceCode}/wmts/1.0.0/WMTSCapabilities.xml")
    public ResponseEntity<String> capabilities(@PathVariable String serviceCode) {
        return xml(raster.capabilities(serviceCode));
    }

    @GetMapping("/VECTOR/{serviceCode}/wmts/1.0.0/{style}/{matrixSet}/{matrix}/{row}/{column}.png")
    public ResponseEntity<Resource> wmtsTile(@PathVariable String serviceCode, @PathVariable String style,
            @PathVariable String matrixSet, @PathVariable String matrix,
            @PathVariable String row, @PathVariable String column) throws IOException {
        return rasterTile(raster.wmtsTile(serviceCode, style, matrixSet, matrix, row, column));
    }

    @GetMapping({"/VECTOR/{serviceCode}/tms", "/VECTOR/{serviceCode}/tms/"})
    public ResponseEntity<String> tmsRoot(@PathVariable String serviceCode) {
        return xml(raster.tmsDescriptor(serviceCode, "root"));
    }

    @GetMapping({"/VECTOR/{serviceCode}/tms/1.0.0", "/VECTOR/{serviceCode}/tms/1.0.0/"})
    public ResponseEntity<String> tmsService(@PathVariable String serviceCode) {
        return xml(raster.tmsDescriptor(serviceCode, "service"));
    }

    @GetMapping("/VECTOR/{serviceCode}/tms/1.0.0/tilemap.xml")
    public ResponseEntity<String> tmsLayer(@PathVariable String serviceCode) {
        return xml(raster.tmsDescriptor(serviceCode, "layer"));
    }

    @GetMapping("/VECTOR/{serviceCode}/tms/1.0.0/{z}/{x}/{y}.png")
    public ResponseEntity<Resource> tmsTile(@PathVariable String serviceCode, @PathVariable String z,
            @PathVariable String x, @PathVariable String y) throws IOException {
        return rasterTile(raster.tmsTile(serviceCode, z, x, y));
    }

    @ExceptionHandler(VectorRasterPublicationService.WmtsException.class)
    public ResponseEntity<String> wmtsError(VectorRasterPublicationService.WmtsException error, HttpServletRequest request) {
        boolean tms = request.getRequestURI().contains("/tms");
        HttpStatus status = tms && error.status() == HttpStatus.BAD_REQUEST ? HttpStatus.NOT_FOUND : error.status();
        return ResponseEntity.status(status).contentType(XML_UTF8).cacheControl(CacheControl.noStore())
                .body(VectorRasterPublicationService.exceptionXml(error, tms));
    }

    private ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(XML_UTF8).cacheControl(CacheControl.noCache()).body(body);
    }

    private ResponseEntity<Resource> rasterTile(Path file) throws IOException {
        long size = Files.size(file), modified = Files.getLastModifiedTime(file).toMillis();
        // 浏览器可缓存文件但必须重新校验，从而在停用后停止后续访问。
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(size)
                .lastModified(modified).eTag("\"" + size + "-" + modified + "\"")
                .cacheControl(CacheControl.noCache()).body(new FileSystemResource(file));
    }

    @GetMapping({"/VECTOR/{serviceCode}","/VECTOR/{serviceCode}/manifest.json"})
    public ResponseEntity<?> vectorManifest(@PathVariable String serviceCode) {
        var result=vectors.descriptor(serviceCode);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).eTag("\"release-"+result.path("releaseVersion").asText()+"\"").body(result);
    }

    @GetMapping("/VECTOR/{serviceCode}/styles/{releaseVersion}/{schemeId}.json")
    public ResponseEntity<?> vectorStyle(@PathVariable String serviceCode,@PathVariable String releaseVersion,@PathVariable String schemeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(vectors.style(serviceCode,releaseVersion,schemeId));
    }

    @GetMapping(value="/VECTOR/{serviceCode}/tiles/{resourceId}/{z}/{x}/{y}.mvt",produces="application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> vectorTile(@PathVariable String serviceCode,@PathVariable Long resourceId,
            @PathVariable int z,@PathVariable int x,@PathVariable int y) throws IOException {
        byte[] body=vectors.tile(serviceCode,resourceId,z,x,y);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).header("Vary","Accept-Encoding")
                .eTag("W/\""+org.ocean.admin.gis.service.VectorRenderPlanner.digest(body)+"\"").body(body);
    }

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
