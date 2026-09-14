package org.ocean.admin.gis.terrain.engine.mago;

import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;

import java.util.ArrayList;
import java.util.List;

/** 将领域参数安全转换为 mago CLI 参数，不经过 Shell。 */
public class MagoCommandBuilder {

    public List<String> build(
            MagoTerrainProperties properties,
            TerrainGenerationRequest request) {
        validateProperties(properties);
        TerrainOptions options = request.options();
        List<String> command = new ArrayList<>();
        command.add(properties.getJavaCommand());
        command.add("-Xmx" + properties.getMaxHeap());
        command.add("-jar");
        command.add(properties.getJarPath().toAbsolutePath().normalize().toString());

        request.inputPaths().forEach(path -> {
            command.add("--input");
            command.add(path.toAbsolutePath().normalize().toString());
        });
        command.add("--output");
        command.add(request.outputPath().toAbsolutePath().normalize().toString());

        if (request.tempPath() != null) {
            command.add("--temp");
            command.add(request.tempPath().toAbsolutePath().normalize().toString());
        }
        if (request.logPath() != null) {
            command.add("--log");
            command.add(request.logPath().toAbsolutePath().normalize().toString());
        }
        command.add("--minDepth");
        command.add(String.valueOf(options.minDepth()));
        if (options.maxDepth() != null) {
            command.add("--maxDepth");
            command.add(String.valueOf(options.maxDepth()));
        }
        command.add("--geoid");
        command.add(options.geoid());
        if (options.noDataValue() != null) {
            command.add("--nodataValue");
            command.add(String.valueOf(options.noDataValue()));
        }
        command.add("--interpolationType");
        command.add(options.interpolationType().commandValue());
        if (options.rasterMaxSize() != null) {
            command.add("--rasterMaxSize");
            command.add(String.valueOf(options.rasterMaxSize()));
        }
        if (options.mosaicSize() != null) {
            command.add("--mosaicSize");
            command.add(String.valueOf(options.mosaicSize()));
        }
        if (!options.calculateNormals()) {
            command.add("--noCalculateNormals");
        }
        if (options.continuePrevious()) {
            command.add("--continue");
        }
        if (options.leaveTemp()) {
            command.add("--leaveTemp");
        }
        return List.copyOf(command);
    }

    /**
     * mago 将 --json 作为独立模式：其 input 必须是已经生成好的地形目录，
     * 不能和 DEM 切片参数放在同一次调用中。
     */
    public List<String> buildLayerJson(
            MagoTerrainProperties properties,
            TerrainGenerationRequest request) {
        validateProperties(properties);
        String terrainPath = request.outputPath().toAbsolutePath().normalize().toString();
        List<String> command = new ArrayList<>();
        command.add(properties.getJavaCommand());
        command.add("-Xmx" + properties.getMaxHeap());
        command.add("-jar");
        command.add(properties.getJarPath().toAbsolutePath().normalize().toString());
        command.add("--input");
        command.add(terrainPath);
        command.add("--output");
        command.add(terrainPath);
        command.add("--json");
        if (!request.options().calculateNormals()) {
            command.add("--noCalculateNormals");
        }
        return List.copyOf(command);
    }

    private void validateProperties(MagoTerrainProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("mago 配置不能为空");
        }
        if (properties.getJavaCommand() == null || properties.getJavaCommand().isBlank()) {
            throw new IllegalArgumentException("mago java-command 不能为空");
        }
        if (properties.getJarPath() == null) {
            throw new IllegalArgumentException("mago jar-path 不能为空");
        }
        if (properties.getMaxHeap() == null
                || !properties.getMaxHeap().matches("(?i)^[1-9]\\d*[kmg]$")) {
            throw new IllegalArgumentException("mago max-heap 必须使用正整数加 k、m 或 g，例如 8g");
        }
        if (properties.getProcessTimeout() == null
                || properties.getProcessTimeout().isZero()
                || properties.getProcessTimeout().isNegative()) {
            throw new IllegalArgumentException("mago process-timeout 必须大于 0");
        }
    }
}
