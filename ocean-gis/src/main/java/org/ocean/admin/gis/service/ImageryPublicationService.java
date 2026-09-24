package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.vo.GisImageryPublicationVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 管理 XYZ 影像瓦片发布，并将公开服务编码解析为受控的本地文件。 */
@Service
@RequiredArgsConstructor
public class ImageryPublicationService {

    private static final String PROCESSING_TYPE = "IMAGERY";
    private static final String IMAGERY_PREFIX = "imagery/";
    private static final Pattern TILE_PATH = Pattern.compile(
            "^(\\d{1,2})/(\\d+)/(\\d+)\\.(png|jpg|jpeg)$",
            Pattern.CASE_INSENSITIVE);

    private final GisPublicationService publicationService;
    private final GisProcessingTaskRecordService taskService;
    private final GisProcessingStorageService processingStorageService;
    private final ObjectMapper objectMapper;
    private final Map<String, PublishedTiles> publishedTiles = new ConcurrentHashMap<>();

    public PageResult<List<GisImageryPublicationVO>> getPublicationPage(
            Integer current,
            Integer size,
            String serviceCode,
            String status,
            Long dataSetId,
            LocalDateTime publishTimeStart,
            LocalDateTime publishTimeEnd) {
        PageResult<List<GisPublication>> page = publicationService.getPublicationPage(
                PROCESSING_TYPE, current, size, serviceCode, status, dataSetId,
                publishTimeStart, publishTimeEnd);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(this::toVO).toList());
    }

    public GisImageryPublicationVO publish(Long sourceTaskId) {
        GisProcessingTask sourceTask = taskService.getRequired(sourceTaskId);
        GisTileSet tileSet = publicationService.getRequiredTileSet(sourceTaskId, PROCESSING_TYPE);
        validateSourceTask(sourceTask, tileSet);
        PublishedTiles tiles = validateTiles(tileSet.getOutputKey());

        GisPublication existing = publicationService.findBySourceTaskId(
                PROCESSING_TYPE, sourceTaskId);
        String serviceCode = existing == null ? generateServiceCode() : existing.getServiceCode();
        GisPublication publication = publicationService.publish(
                tileSet, serviceCode, "影像服务");
        publishedTiles.put(publication.getServiceCode(), tiles);
        return toVO(publication);
    }

    public GisImageryPublicationVO get(String serviceCode) {
        return toVO(publicationService.getRequired(
                PROCESSING_TYPE, normalizeServiceCode(serviceCode)));
    }

    public GisImageryPublicationVO disable(String serviceCode) {
        String normalized = normalizeServiceCode(serviceCode);
        GisPublication publication = publicationService.disable(
                PROCESSING_TYPE, normalized, "影像服务");
        publishedTiles.remove(normalized);
        return toVO(publication);
    }

    /** 空路径映射到 tilejson.json，其余路径仅允许已发布格式的 XYZ 瓦片。 */
    public Path resolvePublishedFile(String serviceCode, String relativePath) {
        String normalizedCode = normalizeServiceCode(serviceCode);
        PublishedTiles tiles = publishedTiles.computeIfAbsent(
                normalizedCode, this::loadPublishedTiles);
        String normalizedPath = normalizeRelativePath(relativePath);
        if (!"tilejson.json".equals(normalizedPath)) {
            validateTilePath(normalizedPath, tiles.metadata());
        }
        Path file = tiles.root().resolve(normalizedPath).normalize();
        if (!file.startsWith(tiles.root()) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("影像资源不存在");
        }
        return file;
    }

    private PublishedTiles loadPublishedTiles(String serviceCode) {
        GisPublication publication = publicationService.getRequired(PROCESSING_TYPE, serviceCode);
        if (!GisPublicationService.PUBLISHED.equals(publication.getStatus())) {
            throw new IllegalStateException("影像服务已停用");
        }
        return validateTiles(publicationService.getPublicationTileSet(publication).getOutputKey());
    }

    private void validateSourceTask(GisProcessingTask task, GisTileSet tileSet) {
        if (!PROCESSING_TYPE.equals(task.getProcessingType())
                || !PROCESSING_TYPE.equals(tileSet.getTileType())) {
            throw new IllegalArgumentException("只能发布影像切片任务: " + task.getId());
        }
        if (!"COMPLETED".equals(task.getTaskStatus())) {
            throw new IllegalStateException("只有 COMPLETED 状态的影像切片任务可以发布");
        }
        if (!"READY".equals(tileSet.getTileSetStatus())
                || tileSet.getOutputKey() == null
                || !tileSet.getOutputKey().startsWith(IMAGERY_PREFIX)) {
            throw new IllegalArgumentException("影像切片任务缺少合法的产物 Key");
        }
    }

    private PublishedTiles validateTiles(String outputKey) {
        Path root = processingStorageService.resolveTiles(outputKey);
        Path tileJsonPath = root.resolve("tilejson.json");
        Path manifestPath = root.resolve("manifest.json");
        if (!Files.isRegularFile(tileJsonPath) || !Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("影像切片缺少 tilejson.json 或 manifest.json: " + root);
        }
        try {
            JsonNode tileJson = objectMapper.readTree(tileJsonPath.toFile());
            JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
            TileMetadata metadata = readMetadata(tileJson, manifest);
            try (Stream<Path> paths = Files.walk(root)) {
                String suffix = "." + metadata.extension();
                if (paths.noneMatch(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))) {
                    throw new IllegalStateException("影像切片目录中没有 " + suffix + " 瓦片: " + root);
                }
            }
            return new PublishedTiles(root, metadata);
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查影像切片目录: " + root, ex);
        }
    }

    private TileMetadata readMetadata(JsonNode tileJson, JsonNode manifest) {
        if (!"xyz".equalsIgnoreCase(tileJson.path("scheme").asText())) {
            throw new IllegalStateException("影像 TileJSON 的 scheme 必须为 xyz");
        }
        if (!"IMAGERY_XYZ".equals(manifest.path("type").asText())
                || !"EPSG:3857".equalsIgnoreCase(manifest.path("targetCrs").asText())
                || !"XYZ".equalsIgnoreCase(manifest.path("tileProfile").asText())) {
            throw new IllegalStateException("影像 manifest 类型、目标坐标系或瓦片剖面不合法");
        }
        String tileJsonFormat = tileJson.path("format").asText().toLowerCase(Locale.ROOT);
        String manifestFormat = manifest.path("format").asText().toUpperCase(Locale.ROOT);
        String format;
        String extension;
        if ("png".equals(tileJsonFormat) && "PNG".equals(manifestFormat)) {
            format = "PNG";
            extension = "png";
        } else if (("jpg".equals(tileJsonFormat) || "jpeg".equals(tileJsonFormat))
                && ("JPG".equals(manifestFormat) || "JPEG".equals(manifestFormat))) {
            format = "JPEG";
            extension = "jpg";
        } else {
            throw new IllegalStateException("影像 TileJSON 与 manifest 的格式不一致");
        }
        int minZoom = tileJson.path("minzoom").asInt(-1);
        int maxZoom = tileJson.path("maxzoom").asInt(-1);
        if (minZoom < 0 || maxZoom > 22 || minZoom > maxZoom) {
            throw new IllegalStateException("影像 TileJSON 层级范围不合法");
        }
        String expectedTemplate = "{z}/{x}/{y}." + extension;
        JsonNode templates = tileJson.path("tiles");
        if (!templates.isArray() || templates.isEmpty()
                || !expectedTemplate.equals(templates.get(0).asText())) {
            throw new IllegalStateException("影像 TileJSON 瓦片模板不合法");
        }
        return new TileMetadata("EPSG:3857", "XYZ", format, extension, minZoom, maxZoom);
    }

    private void validateTilePath(String path, TileMetadata metadata) {
        Matcher matcher = TILE_PATH.matcher(path);
        if (!matcher.matches() || !metadata.extension().equalsIgnoreCase(matcher.group(4))) {
            throw new IllegalArgumentException("非法影像瓦片路径");
        }
        int zoom;
        long x;
        long y;
        try {
            zoom = Integer.parseInt(matcher.group(1));
            x = Long.parseLong(matcher.group(2));
            y = Long.parseLong(matcher.group(3));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("非法影像瓦片坐标", ex);
        }
        long dimension = 1L << zoom;
        if (zoom < metadata.minZoom() || zoom > metadata.maxZoom()
                || x < 0 || y < 0 || x >= dimension || y >= dimension) {
            throw new IllegalArgumentException("影像瓦片坐标超出发布范围");
        }
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || "/".equals(relativePath)) {
            return "tilejson.json";
        }
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String generateServiceCode() {
        return "IMG_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private String normalizeServiceCode(String serviceCode) {
        if (serviceCode == null || !serviceCode.matches("IMG_[A-Fa-f0-9]{20}")) {
            throw new IllegalArgumentException("非法影像服务编码");
        }
        return serviceCode.toUpperCase(Locale.ROOT);
    }

    private GisImageryPublicationVO toVO(GisPublication publication) {
        GisTileSet tileSet = publicationService.getPublicationTileSet(publication);
        String root = publicationService.buildPublicUrl(
                "/imagery/" + publication.getServiceCode() + "/");
        String extension = "PNG".equals(tileSet.getOutputFormat()) ? "png" : "jpg";
        return GisImageryPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .sourceTaskId(tileSet.getTaskId())
                .dataSetId(publication.getDataSetId())
                .status(publication.getStatus())
                .targetCrs(tileSet.getTargetCrs())
                .tileProfile(tileSet.getTileProfile())
                .outputFormat(tileSet.getOutputFormat())
                .minZoom(tileSet.getMinZoom())
                .maxZoom(tileSet.getMaxZoom())
                .serviceUrl(root)
                .tileJsonUrl(root + "tilejson.json")
                .tileUrlTemplate(root + "{z}/{x}/{y}." + extension)
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime())
                .build();
    }

    private record TileMetadata(String targetCrs, String tileProfile, String format,
                                String extension, int minZoom, int maxZoom) { }

    private record PublishedTiles(Path root, TileMetadata metadata) { }
}
