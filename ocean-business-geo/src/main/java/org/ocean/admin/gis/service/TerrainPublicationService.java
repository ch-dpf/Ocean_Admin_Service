package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.dto.TaskStatus;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Quantized Mesh 地形瓦片发布协议。 */
@Service
@RequiredArgsConstructor
public class TerrainPublicationService implements PublicationProtocol {

    private static final GisProcessingType PROCESSING_TYPE = GisProcessingType.TERRAIN;
    private static final String TERRAIN_PREFIX = "terrain/";

    private final PublicationRecordService recordService;
    private final GisProcessingStorageService processingStorageService;
    private final Map<String, PublishedArtifact> publishedArtifacts = new ConcurrentHashMap<>();

    @Override
    public GisProcessingType type() {
        return PROCESSING_TYPE;
    }

    @Override
    public String displayName() {
        return "地形服务";
    }

    @Override
    public PublishedArtifact validateForPublish(GisProcessingTask task, GisTileSet tileSet) {
        validateSourceTask(task, tileSet);
        return new PublishedArtifact(
                validateTiles(tileSet.getOutputKey()), "terrain",
                tileSet.getMinZoom(), tileSet.getMaxZoom(), tileSet.getTargetCrs());
    }

    @Override
    public String generateServiceCode() {
        return "TRN_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeServiceCode(String serviceCode) {
        if (serviceCode == null || !serviceCode.matches("TRN_[A-Fa-f0-9]{20}")) {
            throw new IllegalArgumentException("非法地形服务编码");
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

    /** 解析公开请求中的相对路径；空路径表示请求 layer.json。 */
    @Override
    public Path resolvePublishedFile(String serviceCode, String relativePath) {
        String normalizedCode = normalizeServiceCode(serviceCode);
        PublishedArtifact artifact = publishedArtifacts.computeIfAbsent(
                normalizedCode, this::loadPublishedArtifact);
        String normalizedRelative = relativePath == null || relativePath.isBlank()
                ? "layer.json" : relativePath.replace('\\', '/');
        while (normalizedRelative.startsWith("/")) {
            normalizedRelative = normalizedRelative.substring(1);
        }
        Path file = artifact.root().resolve(normalizedRelative).normalize();
        if (!file.startsWith(artifact.root()) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("地形资源不存在");
        }
        return file;
    }

    @Override
    public PublicationAccess buildAccess(GisPublication publication, GisTileSet tileSet) {
        String root = recordService.buildServiceRoot(PROCESSING_TYPE, publication.getServiceCode());
        return new PublicationAccess(root, root + "layer.json", null);
    }

    private PublishedArtifact loadPublishedArtifact(String serviceCode) {
        GisPublication publication = recordService.getRequired(PROCESSING_TYPE, serviceCode);
        if (!PublicationRecordService.PUBLISHED.equals(publication.getStatus())) {
            throw new IllegalStateException("地形服务已停用");
        }
        GisTileSet tileSet = recordService.getPublicationTileSet(publication);
        return new PublishedArtifact(
                validateTiles(tileSet.getOutputKey()), "terrain",
                tileSet.getMinZoom(), tileSet.getMaxZoom(), tileSet.getTargetCrs());
    }

    private void validateSourceTask(GisProcessingTask task, GisTileSet tileSet) {
        if (PROCESSING_TYPE != task.getProcessingType()
                || PROCESSING_TYPE != tileSet.getTileType()) {
            throw new IllegalArgumentException("只能发布地形切片任务: " + task.getId());
        }
        if (task.getTaskStatus() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("只有 COMPLETED 状态的地形切片任务可以发布");
        }
        if (!"READY".equals(tileSet.getTileSetStatus())
                || tileSet.getOutputKey() == null
                || !tileSet.getOutputKey().startsWith(TERRAIN_PREFIX)) {
            throw new IllegalArgumentException("地形切片任务缺少合法的产物 Key");
        }
    }

    private Path validateTiles(String outputKey) {
        Path tilesRoot = processingStorageService.resolveTiles(outputKey);
        if (!Files.isRegularFile(tilesRoot.resolve("layer.json"))) {
            throw new IllegalStateException("地形切片缺少 layer.json: " + tilesRoot);
        }
        try (Stream<Path> files = Files.walk(tilesRoot)) {
            if (files.noneMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".terrain"))) {
                throw new IllegalStateException("地形切片目录中没有 .terrain 文件: " + tilesRoot);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查地形切片目录: " + tilesRoot, ex);
        }
        return tilesRoot;
    }
}
