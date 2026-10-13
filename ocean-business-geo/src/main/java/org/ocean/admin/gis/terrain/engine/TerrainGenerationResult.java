package org.ocean.admin.gis.terrain.engine;

import java.nio.file.Path;
import java.time.Duration;

/** 地形引擎成功执行后的结果。 */
public record TerrainGenerationResult(
        Path outputPath,
        Duration elapsed,
        int exitCode) {
}
