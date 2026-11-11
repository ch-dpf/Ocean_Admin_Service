package org.ocean.admin.gis.storage;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorResource;
import org.ocean.admin.gis.mapper.GisVectorFeatureMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** PostGIS 查询与主键游标导出，空间过滤继续由数据库执行。 */
@Component
@RequiredArgsConstructor
public class PostgisVectorFeatureStore implements VectorFeatureStore {
    private final GisVectorFeatureMapper features;

    @Override public String storageType() { return "POSTGIS"; }

    @Override
    public List<GisVectorFeature> features(GisVectorLayer layer, GisVectorResource resource,
            long after, int limit, double[] bbox, Long featureId) {
        if (featureId != null) {
            var feature = features.feature(layer.getId(), featureId);
            return feature == null ? List.of() : List.of(feature);
        }
        if (bbox != null) return features.features(layer.getId(), after, limit, bbox[0], bbox[1], bbox[2], bbox[3]);
        return features.exportFeatures(layer.getId(), after, limit);
    }

    @Override
    public void export(GisVectorResource resource, List<GisVectorLayer> layers, BatchConsumer consumer) throws IOException {
        for (var layer : layers) {
            long after = 0;
            while (true) {
                var batch = features.exportFeatures(layer.getId(), after, 100);
                if (batch.isEmpty()) break;
                consumer.accept(batch);
                after = batch.getLast().getId();
            }
        }
    }
}
