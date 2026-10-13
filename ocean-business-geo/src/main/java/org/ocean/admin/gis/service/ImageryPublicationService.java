package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.imagery.ImageryTileOptions;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** XYZ/TMS 影像瓦片发布协议。 */
@Service
@RequiredArgsConstructor
public class ImageryPublicationService implements PublicationProtocol {

    private static final GisProcessingType PROCESSING_TYPE = GisProcessingType.IMAGERY;
    private static final String IMAGERY_PREFIX = "imagery/";
    private static final Pattern TILE_PATH = Pattern.compile(
            "^(\\d{1,2})/(\\d+)/(\\d+)\\.(png|jpg|jpeg)$",
            Pattern.CASE_INSENSITIVE);

    private final PublicationRecordService recordService;
    private final GisProcessingStorageService processingStorageService;
    private final ObjectMapper objectMapper;
    private final Map<String, PublishedArtifact> publishedArtifacts = new ConcurrentHashMap<>();

    @Override
    public GisProcessingType type() {
        return PROCESSING_TYPE;
    }

    @Override
    public String displayName() {
        return "影像服务";
    }

    @Override
    public PublishedArtifact validateForPublish(GisProcessingTask task, GisTileSet tileSet) {
        validateSourceTask(task, tileSet);
        PublishedTiles tiles = validateTiles(tileSet.getOutputKey());
        validateTileSetMetadata(tileSet, tiles.metadata());
        TileMetadata metadata = tiles.metadata();
        return new PublishedArtifact(
                tiles.root(), metadata.extension(), metadata.minZoom(),
                metadata.maxZoom(), metadata.targetCrs());
    }

    @Override
    public String generateServiceCode() {
        return "IMG_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeServiceCode(String serviceCode) {
        if (serviceCode == null || !serviceCode.matches("IMG_[A-Fa-f0-9]{20}")) {
            throw new IllegalArgumentException("非法影像服务编码");
        }
        return serviceCode.toUpperCase(Locale.ROOT);
    }

    @Override
    public void activate(String serviceCode, PublishedArtifact artifact) {
        publishedArtifacts.put(normalizeServiceCode(serviceCode), artifact);
    }

    @Override
    public void deactivate(String serviceCode) {
        publishedArtifacts.remove(normalizeServiceCode(serviceCode));
    }

    /** 空路径映射到 tilejson.json，其余路径仅允许已发布格式的影像瓦片。 */
    @Override
    public Path resolvePublishedFile(String serviceCode, String relativePath) {
        String normalizedCode = normalizeServiceCode(serviceCode);
        PublishedArtifact artifact = publishedArtifacts.computeIfAbsent(
                normalizedCode, this::loadPublishedArtifact);
        String normalizedPath = normalizeRelativePath(relativePath);
        if (!"tilejson.json".equals(normalizedPath)) {
            validateTilePath(normalizedPath, artifact);
        }
        Path file = artifact.root().resolve(normalizedPath).normalize();
        if (!file.startsWith(artifact.root()) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("影像资源不存在");
        }
        return file;
    }

    @Override
    public PublicationAccess buildAccess(GisPublication publication, GisTileSet tileSet) {
        String root = recordService.buildServiceRoot(PROCESSING_TYPE, publication.getServiceCode());
        String extension = "PNG".equalsIgnoreCase(tileSet.getOutputFormat()) ? "png" : "jpg";
        return new PublicationAccess(
                root, root + "tilejson.json", root + "{z}/{x}/{y}." + extension);
    }

    private PublishedArtifact loadPublishedArtifact(String serviceCode) {
        GisPublication publication = recordService.getRequired(PROCESSING_TYPE, serviceCode);
        if (!PublicationRecordService.PUBLISHED.equals(publication.getStatus())) {
            throw new IllegalStateException("影像服务已停用");
        }
        GisTileSet tileSet = recordService.getPublicationTileSet(publication);
        PublishedTiles tiles = validateTiles(tileSet.getOutputKey());
        validateTileSetMetadata(tileSet, tiles.metadata());
        TileMetadata metadata = tiles.metadata();
        return new PublishedArtifact(
                tiles.root(), metadata.extension(), metadata.minZoom(),
                metadata.maxZoom(), metadata.targetCrs());
    }

    private void validateSourceTask(GisProcessingTask task, GisTileSet tileSet) {
        if (PROCESSING_TYPE != task.getProcessingType()
                || PROCESSING_TYPE != tileSet.getTileType()) {
            throw new IllegalArgumentException("只能发布影像切片任务: " + task.getId());
        }
        if (task.getTaskStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("只有 COMPLETED 状态的影像切片任务可以发布");
        }
        if (!"READY".equals(tileSet.getTileSetStatus())
                || tileSet.getOutputKey() == null
                || !tileSet.getOutputKey().startsWith(IMAGERY_PREFIX)) {
            throw new IllegalArgumentException("影像切片任务缺少合法的产物 Key");
        }
    }

    private void validateTileSetMetadata(GisTileSet tileSet, TileMetadata metadata) {
        if (!metadata.targetCrs().equalsIgnoreCase(tileSet.getTargetCrs())
                || !metadata.tileProfile().equalsIgnoreCase(tileSet.getTileProfile())
                || !metadata.format().equalsIgnoreCase(tileSet.getOutputFormat())
                || !Integer.valueOf(metadata.minZoom()).equals(tileSet.getMinZoom())
                || !Integer.valueOf(metadata.maxZoom()).equals(tileSet.getMaxZoom())) {
            throw new IllegalStateException("影像瓦片产物元数据与瓦片集记录不一致");
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
                        && path.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(suffix))) {
                    throw new IllegalStateException(
                            "影像切片目录中没有 " + suffix + " 瓦片: " + root);
                }
            }
            return new PublishedTiles(root, metadata);
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查影像切片目录: " + root, ex);
        }
    }

    private TileMetadata readMetadata(JsonNode tileJson, JsonNode manifest) {
        ImageryTileOptions.TileProfile profile;
        ImageryTileOptions.TargetCrs targetCrs;
        try {
            profile = ImageryTileOptions.TileProfile.valueOf(
                    tileJson.path("scheme").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("影像 TileJSON 的 scheme 必须为 xyz 或 tms", ex);
        }
        try {
            targetCrs = ImageryTileOptions.TargetCrs.fromCode(
                    manifest.path("targetCrs").asText());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "影像 manifest 的目标坐标系必须为 EPSG:3857 或 EPSG:4326", ex);
        }
        String tileJsonCrs = tileJson.path("crs").asText();
        String tileJsonMatrixSet = tileJson.path("tileMatrixSet").asText();
        String manifestMatrixSet = manifest.path("tileMatrixSet").asText();
        if (!profile.manifestType().equalsIgnoreCase(manifest.path("type").asText())
                || !profile.name().equalsIgnoreCase(manifest.path("tileProfile").asText())
                || (!tileJsonCrs.isBlank()
                        && !targetCrs.code().equalsIgnoreCase(tileJsonCrs))
                || (!tileJsonMatrixSet.isBlank()
                        && !targetCrs.tileMatrixSet().equalsIgnoreCase(tileJsonMatrixSet))
                || (!manifestMatrixSet.isBlank()
                        && !targetCrs.tileMatrixSet().equalsIgnoreCase(manifestMatrixSet))) {
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
        return new TileMetadata(
                targetCrs.code(), profile.name(), format, extension, minZoom, maxZoom);
    }

    private void validateTilePath(String path, PublishedArtifact artifact) {
        Matcher matcher = TILE_PATH.matcher(path);
        if (!matcher.matches()
                || !artifact.extension().equalsIgnoreCase(matcher.group(4))) {
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
        if (zoom < artifact.minZoom() || zoom > artifact.maxZoom()) {
            throw new IllegalArgumentException("影像瓦片坐标超出发布范围");
        }
        ImageryTileOptions.TargetCrs targetCrs =
                ImageryTileOptions.TargetCrs.fromCode(artifact.targetCrs());
        long matrixWidth = targetCrs.matrixWidth(zoom);
        long matrixHeight = targetCrs.matrixHeight(zoom);
        if (x < 0 || y < 0 || x >= matrixWidth || y >= matrixHeight) {
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

    private record TileMetadata(
            String targetCrs,
            String tileProfile,
            String format,
            String extension,
            int minZoom,
            int maxZoom) {
    }

    private record PublishedTiles(Path root, TileMetadata metadata) {
    }
}
