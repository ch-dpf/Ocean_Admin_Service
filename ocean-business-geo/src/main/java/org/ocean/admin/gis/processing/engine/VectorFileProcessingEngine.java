package org.ocean.admin.gis.processing.engine;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.config.VectorTileProperties;
import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.dto.GisProcessingParameters.VectorProcessingParameters;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.service.GeoPackageStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 从任务专属快照生成静态矢量成果，整批切片在同一隔离进程内完成。 */
@Component
@RequiredArgsConstructor
public class VectorFileProcessingEngine implements GisFileProcessingEngine {
    private final GeoPackageStore store;
    private final ObjectMapper json;
    private final VectorTileProperties limits;

    @Override public GisProcessingType type() { return GisProcessingType.VECTOR; }

    @Override public void validate(String name, String extension, Path path) {
        if (!"gpkg".equals(extension)) throw new IllegalArgumentException("矢量切片输入须为固定的GPKG工作快照");
    }

    public static void validateParameters(VectorProcessingParameters p) {
        if (!"EPSG:3857".equals(p.targetCrs()) || !"XYZ".equals(p.tileProfile()) || !"MVT".equals(p.outputFormat())
                || p.dataSetId() == null || p.dataSetId() < 1 || p.dataRevision() == null || p.dataRevision() < 0
                || p.minZoom() == null || p.maxZoom() == null || p.minZoom() < 0 || p.maxZoom() > 18 || p.minZoom() > p.maxZoom())
            throw new IllegalArgumentException("静态矢量瓦片须使用EPSG:3857、XYZ、MVT及0至18级范围");
        List<Double> b = p.bounds();
        if (b == null || b.size() != 4 || b.stream().anyMatch(n -> n == null || !Double.isFinite(n))
                || b.get(0) < -180 || b.get(2) > 180 || b.get(1) < -85.05112878 || b.get(3) > 85.05112878
                || b.get(0) >= b.get(2) || b.get(1) >= b.get(3))
            throw new IllegalArgumentException("切片范围须为有效的经纬度矩形，纬度在墨卡托范围内");
        if (p.schemeSnapshot() == null || !p.schemeSnapshot().path("definition").isObject()
                || !p.dataSetId().toString().equals(p.schemeSnapshot().path("dataSetId").asText()))
            throw new IllegalArgumentException("样式快照与工作数据集不一致");
    }

    /** 每级只保存矩形索引范围，避免预先分配全部瓦片坐标。 */
    public static List<int[]> tileRanges(VectorProcessingParameters p) {
        validateParameters(p);
        List<int[]> ranges = new ArrayList<>();
        for (int z = p.minZoom(); z <= p.maxZoom(); z++) {
            int n = 1 << z;
            int minX = Math.max(0, (int) Math.floor((p.bounds().get(0) + 180) / 360 * n));
            int maxX = Math.min(n - 1, (int) Math.ceil((p.bounds().get(2) + 180) / 360 * n) - 1);
            double north = Math.toRadians(p.bounds().get(3)), south = Math.toRadians(p.bounds().get(1));
            int minY = Math.max(0, (int) Math.floor((1 - Math.log(Math.tan(north) + 1 / Math.cos(north)) / Math.PI) / 2 * n));
            int maxY = Math.min(n - 1, (int) Math.ceil((1 - Math.log(Math.tan(south) + 1 / Math.cos(south)) / Math.PI) / 2 * n) - 1);
            ranges.add(new int[]{z, minX, Math.max(minX, maxX), minY, Math.max(minY, maxY)});
        }
        return ranges;
    }

    @Override public void process(List<Path> inputs, GisProcessingWorkspace workspace, GisProcessingParameters parameters,
                                  Consumer<ProgressStage> progress) {
        if (!(parameters instanceof VectorProcessingParameters p) || inputs.size() != 1)
            throw new IllegalArgumentException("静态矢量切片任务参数无效");
        List<int[]> ranges = tileRanges(p);
        long total = ranges.stream().mapToLong(r -> (long) (r[2] - r[1] + 1) * (r[4] - r[3] + 1)).sum();
        if (total > limits.getMaxStaticTileCount()) throw new IllegalArgumentException("切片数量超过上限，请缩小范围或层级");
        try {
            Files.createDirectories(workspace.outputPath());
            var definition = json.readTree(inputs.getFirst().resolveSibling("source.json").toFile());
            progress.accept(ProgressStage.indeterminate("VECTOR_TILES", "批量生成静态矢量瓦片，共" + total + "块"));
            store.staticTiles(inputs.getFirst(), definition, ranges, workspace.outputPath(), limits.getMaxDecodedTileBytes(),
                    limits.getMaxTileBytes(), () -> {
                        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("静态矢量任务已中断");
                    });
            json.writeValue(workspace.outputPath().resolve("style.json").toFile(), p.schemeSnapshot());
            var manifest = json.createObjectNode().put("schemaVersion", 1).put("kind", "STATIC_VECTOR_TILE_SET")
                    .put("targetCrs", p.targetCrs()).put("tileProfile", p.tileProfile()).put("format", p.outputFormat())
                    .put("dataSetId", p.dataSetId().toString()).put("dataRevision", p.dataRevision())
                    .put("tileVersion", workspace.outputKey()).put("minZoom", p.minZoom()).put("maxZoom", p.maxZoom())
                    .put("nativeMaxZoom", p.maxZoom()).put("displayMaxZoom", 24).put("tileSize", 512)
                    .put("tiles", "{z}/{x}/{y}.mvt").put("style", "style.json").put("tileCount", total);
            manifest.set("bounds", json.valueToTree(p.bounds()));
            manifest.set("scheme", p.schemeSnapshot());
            json.writeValue(workspace.outputPath().resolve("manifest.json").toFile(), manifest);
            progress.accept(ProgressStage.determinate("VECTOR_TILES", "静态瓦片及样式快照生成完成", total, total, ProgressUnit.TILE));
        } catch (Exception ex) { throw new IllegalStateException("生成静态矢量瓦片失败: " + ex.getMessage(), ex); }
    }
}
