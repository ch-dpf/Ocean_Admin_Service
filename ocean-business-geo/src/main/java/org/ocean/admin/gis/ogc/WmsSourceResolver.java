package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 为已发布图层定位可供 WMS 动态渲染的 GeoTIFF 源文件。
 *
 * <p>切片任务在写 {@code manifest.json} 时记录了每个源文件的绝对路径，WMS 直接复用这些路径。
 * 源文件被移动或删除时返回空列表，该图层降级为只能通过 WMTS 读取已生成的静态瓦片，
 * 不做跨任务的缓存目录猜测，避免误取到其它数据集的切片。</p>
 */
@Slf4j
@Service
public class WmsSourceResolver {

    private static final String MANIFEST = "manifest.json";

    private final GisProcessingStorageService storageService;
    private final ObjectMapper objectMapper;

    public WmsSourceResolver(GisProcessingStorageService storageService,
            ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取瓦片集声明的源 GeoTIFF，只保留仍存在且可读的文件。
     *
     * @param tileSet 图层关联的瓦片集实体
     * @return 可渲染的源文件列表；全部不可用时返回空列表
     */
    public List<Path> resolveSources(GisTileSet tileSet) {
        if (tileSet == null || tileSet.getOutputKey() == null) {
            return Collections.emptyList();
        }
        Path manifestPath;
        try {
            manifestPath = storageService.resolveTiles(tileSet.getOutputKey()).resolve(MANIFEST);
        } catch (RuntimeException ex) {
            log.debug("[OGC][WMS] 无法定位切片目录: {}", tileSet.getOutputKey(), ex);
            return Collections.emptyList();
        }
        if (!Files.isRegularFile(manifestPath)) {
            return Collections.emptyList();
        }
        List<Path> sources = new ArrayList<>();
        for (Path declared : readDeclaredSources(manifestPath)) {
            if (Files.isReadable(declared)) {
                sources.add(declared);
            } else {
                log.debug("[OGC][WMS] 源文件已不可读，跳过: {}", declared);
            }
        }
        return sources;
    }

    private List<Path> readDeclaredSources(Path manifestPath) {
        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(manifestPath.toFile());
        } catch (RuntimeException ex) {
            log.warn("[OGC][WMS] 读取 manifest 失败: {}", manifestPath, ex);
            return Collections.emptyList();
        }
        JsonNode sourcesNode = manifest.path("sources");
        if (!sourcesNode.isArray()) {
            return Collections.emptyList();
        }
        List<Path> paths = new ArrayList<>();
        for (JsonNode source : sourcesNode) {
            String declared = source.path("path").asText("");
            if (!declared.isBlank()) {
                paths.add(Path.of(declared).toAbsolutePath().normalize());
            }
        }
        return paths;
    }
}
