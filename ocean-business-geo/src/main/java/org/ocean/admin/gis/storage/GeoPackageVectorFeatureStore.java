package org.ocean.admin.gis.storage;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.service.GeoPackageStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/** GPKG 查询与批量导出，同一资源仅打开一次并始终清理导出中间文件。 */
@Component
@RequiredArgsConstructor
public class GeoPackageVectorFeatureStore implements VectorFeatureStore {
    private final GeoPackageStore packages;
    private final ObjectMapper json;

    @Override public String storageType() { return "GPKG"; }

    @Override
    public List<GisVectorFeature> features(GisVectorLayer layer, GisVectorResource resource,
            long after, int limit, double[] bbox, Long featureId) throws IOException {
        return StreamSupport.stream(packages.query(resource.getStorageKey(), "l_" + layer.getId(),
                        after, limit, bbox, featureId).path("items").spliterator(), false)
                .map(row -> json.treeToValue(row.path("value"), GisVectorFeature.class)).toList();
    }

    @Override
    public void export(GisVectorResource resource, List<GisVectorLayer> layers, BatchConsumer consumer) throws IOException {
        long temporaryId = IdWorker.getId();
        var output = packages.path(temporaryId + ".ndjson");
        try {
            packages.export(resource.getStorageKey(), layers.stream().map(GisVectorLayer::getId).toList(), temporaryId);
            try (var input = Files.newBufferedReader(output)) {
                var batch = new ArrayList<GisVectorFeature>(100);
                String line;
                while ((line = input.readLine()) != null) {
                    batch.add(json.readValue(line, GisVectorFeature.class));
                    if (batch.size() == 100) {
                        consumer.accept(batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) consumer.accept(batch);
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
