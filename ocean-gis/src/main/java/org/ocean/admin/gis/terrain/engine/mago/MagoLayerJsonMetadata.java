package org.ocean.admin.gis.terrain.engine.mago;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 修正 mago 独立 --json 模式产生的反序纬度 bounds。 */
final class MagoLayerJsonMetadata {

    private static final Pattern BOUNDS_PATTERN = Pattern.compile(
            "\\\"bounds\\\"\\s*:\\s*\\[([^]]+)]");

    private MagoLayerJsonMetadata() {
    }

    static double[] readValidBounds(Path terrainDirectory) {
        Path layerJson = terrainDirectory.toAbsolutePath().normalize().resolve("layer.json");
        String json = read(layerJson);
        Matcher matcher = BOUNDS_PATTERN.matcher(json);
        if (!matcher.find()) {
            throw new MagoTerrainException("mago layer.json 缺少 bounds: " + layerJson);
        }
        String[] values = matcher.group(1).split(",");
        if (values.length != 4) {
            throw new MagoTerrainException("mago layer.json bounds 格式无效: " + layerJson);
        }
        double[] bounds = new double[4];
        try {
            for (int i = 0; i < values.length; i++) {
                bounds[i] = Double.parseDouble(values[i].trim());
                if (!Double.isFinite(bounds[i])) {
                    throw new NumberFormatException("non-finite bound");
                }
            }
        } catch (NumberFormatException ex) {
            throw new MagoTerrainException("mago layer.json bounds 不是有效数值: " + layerJson, ex);
        }
        if (bounds[0] > bounds[2] || bounds[1] > bounds[3]) {
            throw new MagoTerrainException("mago 切片阶段生成了反序 bounds: " + layerJson);
        }
        return bounds;
    }

    static void restoreBounds(Path terrainDirectory, double[] bounds) {
        Path layerJson = terrainDirectory.toAbsolutePath().normalize().resolve("layer.json");
        String json = read(layerJson);
        Matcher matcher = BOUNDS_PATTERN.matcher(json);
        if (!matcher.find()) {
            throw new MagoTerrainException("mago 重建后的 layer.json 缺少 bounds: " + layerJson);
        }
        String replacement = "\"bounds\":["
                + bounds[0] + "," + bounds[1] + "," + bounds[2] + "," + bounds[3] + "]";
        try {
            Files.writeString(
                    layerJson,
                    matcher.replaceFirst(Matcher.quoteReplacement(replacement)),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new MagoTerrainException("无法修正 mago layer.json bounds: " + layerJson, ex);
        }
    }

    private static String read(Path layerJson) {
        try {
            return Files.readString(layerJson, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new MagoTerrainException("无法读取 mago layer.json: " + layerJson, ex);
        }
    }
}
