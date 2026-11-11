package org.ocean.admin.gis.storage;

import org.ocean.admin.gis.entity.GisVectorFeature;
import org.ocean.admin.gis.entity.GisVectorLayer;
import org.ocean.admin.gis.entity.GisVectorResource;

import java.io.IOException;
import java.util.List;

/** 工作要素的查询与有界导出；事务、版本及写入恢复由业务层统一协调。 */
public interface VectorFeatureStore {
    String storageType();

    List<GisVectorFeature> features(GisVectorLayer layer, GisVectorResource resource,
            long after, int limit, double[] bbox, Long featureId) throws IOException;

    void export(GisVectorResource resource, List<GisVectorLayer> layers, BatchConsumer consumer) throws IOException;

    @FunctionalInterface
    interface BatchConsumer {
        void accept(List<GisVectorFeature> features) throws IOException;
    }
}
