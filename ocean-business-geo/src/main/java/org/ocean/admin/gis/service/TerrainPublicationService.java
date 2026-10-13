package org.ocean.admin.gis.service;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisProcessingTask;
import org.ocean.admin.gis.entity.GisPublication;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.vo.GisTerrainPublicationVO;
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
import java.util.stream.Stream;

/** 管理地形发布协议，并复用通用 GIS 发布记录和状态服务。 */
@Service
@RequiredArgsConstructor
public class TerrainPublicationService {

    private static final String PROCESSING_TYPE = "TERRAIN";
    private static final String TERRAIN_PREFIX = "terrain/";

    private final GisPublicationService publicationService;
    private final GisProcessingTaskRecordService taskService;
    private final GisProcessingStorageService processingStorageService;
    private final Map<String, Path> publishedRoots = new ConcurrentHashMap<>();

    public PageResult<List<GisTerrainPublicationVO>> getPublicationPage(
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

    public GisTerrainPublicationVO publish(Long sourceTaskId) {
        GisProcessingTask sourceTask = taskService.getRequired(sourceTaskId);
        GisTileSet tileSet = publicationService.getRequiredTileSet(sourceTaskId, PROCESSING_TYPE);
        validateSourceTask(sourceTask, tileSet);
        Path tilesRoot = validateTiles(tileSet.getOutputKey());
        GisPublication existing = publicationService.findBySourceTaskId(
                PROCESSING_TYPE, sourceTaskId);
        String serviceCode = existing == null ? generateServiceCode() : existing.getServiceCode();
        GisPublication publication = publicationService.publish(
                tileSet, serviceCode, "地形服务");
        publishedRoots.put(publication.getServiceCode(), tilesRoot);
        return toVO(publication);
    }

    public GisTerrainPublicationVO get(String serviceCode) {
        return toVO(publicationService.getRequired(
                PROCESSING_TYPE, normalizeServiceCode(serviceCode)));
    }

    public GisTerrainPublicationVO disable(String serviceCode) {
        String normalized = normalizeServiceCode(serviceCode);
        GisPublication publication = publicationService.disable(
                PROCESSING_TYPE, normalized, "地形服务");
        publishedRoots.remove(normalized);
        return toVO(publication);
    }

    /** 解析公开请求中的相对路径；空路径表示请求 layer.json。 */
    public Path resolvePublishedFile(String serviceCode, String relativePath) {
        String normalizedCode = normalizeServiceCode(serviceCode);
        Path tilesRoot = publishedRoots.computeIfAbsent(normalizedCode, this::loadPublishedRoot);
        String normalizedRelative = relativePath == null || relativePath.isBlank()
                ? "layer.json" : relativePath.replace('\\', '/');
        while (normalizedRelative.startsWith("/")) {
            normalizedRelative = normalizedRelative.substring(1);
        }
        Path file = tilesRoot.resolve(normalizedRelative).normalize();
        if (!file.startsWith(tilesRoot) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("地形资源不存在");
        }
        return file;
    }

    private Path loadPublishedRoot(String serviceCode) {
        GisPublication publication = publicationService.getRequired(PROCESSING_TYPE, serviceCode);
        if (!GisPublicationService.PUBLISHED.equals(publication.getStatus())) {
            throw new IllegalStateException("地形服务已停用");
        }
        return validateTiles(publicationService.getPublicationTileSet(publication).getOutputKey());
    }

    private void validateSourceTask(GisProcessingTask task, GisTileSet tileSet) {
        if (!PROCESSING_TYPE.equals(task.getProcessingType())
                || !PROCESSING_TYPE.equals(tileSet.getTileType())) {
            throw new IllegalArgumentException("只能发布地形切片任务: " + task.getId());
        }
        if (!"COMPLETED".equals(task.getTaskStatus())) {
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

    private String generateServiceCode() {
        return "TRN_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private String normalizeServiceCode(String serviceCode) {
        if (serviceCode == null || !serviceCode.matches("TRN_[A-Fa-f0-9]{20}")) {
            throw new IllegalArgumentException("非法地形服务编码");
        }
        return serviceCode.toUpperCase(Locale.ROOT);
    }

    private GisTerrainPublicationVO toVO(GisPublication publication) {
        GisTileSet tileSet = publicationService.getPublicationTileSet(publication);
        return GisTerrainPublicationVO.builder()
                .id(publication.getId())
                .serviceCode(publication.getServiceCode())
                .sourceTaskId(tileSet.getTaskId())
                .dataSetId(publication.getDataSetId())
                .status(publication.getStatus())
                .serviceUrl(publicationService.buildPublicUrl(
                        "/terrain/" + publication.getServiceCode() + "/"))
                .publishTime(publication.getPublishTime())
                .updateTime(publication.getUpdateTime())
                .build();
    }
}
