package org.ocean.admin.gis.ogc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.gis.entity.GisTileSet;
import org.ocean.admin.gis.processing.GisProcessingStorageService;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.service.GisStorageService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 从成果的持久输入引用定位 WMS 源文件，路径边界与数据接入共用同一规则。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsSourceResolver {
    private final GisProcessingStorageService processing;
    private final GisStorageService storage;
    private final ObjectMapper json;
    private final ControlledWorkspaceResolver workspaces;

    public List<Path> resolveSources(GisTileSet tileSet) {
        if (tileSet == null || tileSet.getOutputKey() == null) return List.of();
        List<Path> sources = new ArrayList<>();
        try {
            Path manifest = processing.resolveTiles(tileSet.getOutputKey()).resolve("manifest.json");
            if (!Files.isRegularFile(manifest)) return List.of();
            for (var input : json.readTree(manifest.toFile()).path("inputs")) {
                try {
                    Path source;
                    if (input.hasNonNull("workspaceCode")) {
                        source = workspaces.resolve(input.path("workspaceCode").asText(), input.path("relativePath").asText());
                    } else {
                        source = storage.resolveOriginal(input.path("storageKey").asText());
                    }
                    // 当前动态栅格渲染器读取 GeoTIFF；其他输入格式仍由静态瓦片提供成果。
                    String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if ((name.endsWith(".tif") || name.endsWith(".tiff"))
                            && Files.isRegularFile(source) && Files.isReadable(source)) sources.add(source);
                } catch (IllegalArgumentException ex) {
                    log.debug("[OGC][WMS] 成果输入不可用: tileSetId={}", tileSet.getId(), ex);
                }
            }
        } catch (Exception ex) {
            log.warn("[OGC][WMS] 读取成果输入引用失败: tileSetId={}", tileSet.getId(), ex);
        }
        return sources;
    }
}
