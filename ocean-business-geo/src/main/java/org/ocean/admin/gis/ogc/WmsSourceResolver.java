package org.ocean.admin.gis.ogc;

import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 为已发布图层定位可供 WMS 动态渲染的 GeoTIFF 源文件。
 *
 * <p>切片任务在写 {@code manifest.json} 时记录了当时读取的源文件路径。已管理文件组处理时
 * 这些路径指向原生读取的临时目录，任务结束后会被清理，因此 manifest 路径不可读时回退到
 * 任务输入登记的原始源文件；两者都不可用时返回空列表，该图层降级为只能通过 WMTS 读取
 * 已生成的静态瓦片。</p>
 */
@Slf4j
@Service
public class WmsSourceResolver {

    private static final String MANIFEST = "manifest.json";
    private static final Set<String> RASTER_EXTENSIONS = Set.of("tif", "tiff");

    private final GisProcessingStorageService storageService;
    private final ObjectMapper objectMapper;
    private final GisProcessingInputMapper inputMapper;
    private final GisFileMetaMapper fileMetaMapper;
    private final ControlledWorkspaceResolver workspaceResolver;

    public WmsSourceResolver(GisProcessingStorageService storageService,
            ObjectMapper objectMapper,
            GisProcessingInputMapper inputMapper,
            GisFileMetaMapper fileMetaMapper,
            ControlledWorkspaceResolver workspaceResolver) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.inputMapper = inputMapper;
        this.fileMetaMapper = fileMetaMapper;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * 读取瓦片集对应的源 GeoTIFF，只保留仍存在且可读的文件。
     *
     * @param tileSet 图层关联的瓦片集实体
     * @return 可渲染的源文件列表；全部不可用时返回空列表
     */
    public List<Path> resolveSources(GisTileSet tileSet) {
        if (tileSet == null || tileSet.getOutputKey() == null) {
            return Collections.emptyList();
        }
        List<Path> declared = readManifestSources(tileSet.getOutputKey());
        List<Path> readable = filterReadable(declared, "manifest");
        if (!readable.isEmpty()) {
            return readable;
        }
        // 切片期读取的临时源已被清理，回退到任务输入登记的原始文件
        return filterReadable(originalSources(tileSet.getTaskId()), "原始文件");
    }

    private List<Path> filterReadable(List<Path> candidates, String origin) {
        List<Path> sources = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.isReadable(candidate)) {
                sources.add(candidate);
            } else {
                log.debug("[OGC][WMS] {}源文件已不可读，跳过: {}", origin, candidate);
            }
        }
        return sources;
    }

    /** 读取 manifest 声明的源文件；描述文件缺失或非法时返回空列表。 */
    private List<Path> readManifestSources(String outputKey) {
        Path manifestPath;
        try {
            manifestPath = storageService.resolveTiles(outputKey).resolve(MANIFEST);
        } catch (RuntimeException ex) {
            log.debug("[OGC][WMS] 无法定位切片目录: {}", outputKey, ex);
            return Collections.emptyList();
        }
        if (!Files.isRegularFile(manifestPath)) {
            return Collections.emptyList();
        }
        return readDeclaredSources(manifestPath);
    }

    /** 按任务输入登记的源文件元数据定位原始 GeoTIFF；不存在的成员直接跳过。 */
    private List<Path> originalSources(Long taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        Set<Path> paths = new LinkedHashSet<>();
        for (GisProcessingInput input : inputMapper.selectByTaskId(taskId)) {
            Long fileMetaId = input.getSourceFileMetaId() != null
                    ? input.getSourceFileMetaId() : input.getFileMetaId();
            if (fileMetaId == null) {
                continue;
            }
            GisFileMeta meta = fileMetaMapper.selectById(fileMetaId);
            if (meta == null || !isRaster(meta)) {
                continue;
            }
            try {
                paths.add(workspaceResolver.resolveFile(meta).toAbsolutePath().normalize());
            } catch (RuntimeException ex) {
                log.debug("[OGC][WMS] 原始源文件无法定位: {}", meta.getOriginalName(), ex);
            }
        }
        return new ArrayList<>(paths);
    }

    private boolean isRaster(GisFileMeta meta) {
        String extension = meta.getExtension() == null
                ? "" : meta.getExtension().toLowerCase(Locale.ROOT);
        return RASTER_EXTENSIONS.contains(extension);
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
