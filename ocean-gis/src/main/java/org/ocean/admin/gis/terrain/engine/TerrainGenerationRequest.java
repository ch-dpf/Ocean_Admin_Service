package org.ocean.admin.gis.terrain.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** mago 地形生成所需的不可变输入。 */
public record TerrainGenerationRequest(
        List<Path> inputPaths,
        Path outputPath,
        Path tempPath,
        Path logPath,
        TerrainOptions options) {

    public TerrainGenerationRequest {
        if (inputPaths == null || inputPaths.isEmpty()) {
            throw new IllegalArgumentException("地形切片至少需要一个输入文件");
        }
        inputPaths = List.copyOf(inputPaths);
        if (inputPaths.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("地形切片输入路径不能为空");
        }
        Objects.requireNonNull(outputPath, "地形切片输出路径不能为空");
        options = options == null ? TerrainOptions.defaults() : options;
    }
}
