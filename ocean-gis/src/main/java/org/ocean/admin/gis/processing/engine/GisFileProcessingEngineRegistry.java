package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.processing.GisProcessingType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按处理类型选择对应切片引擎。 */
@Component
public class GisFileProcessingEngineRegistry {

    private final Map<GisProcessingType, GisFileProcessingEngine> engines;

    public GisFileProcessingEngineRegistry(List<GisFileProcessingEngine> candidates) {
        EnumMap<GisProcessingType, GisFileProcessingEngine> mapped =
                new EnumMap<>(GisProcessingType.class);
        for (GisFileProcessingEngine engine : candidates) {
            if (mapped.putIfAbsent(engine.type(), engine) != null) {
                throw new IllegalStateException("处理类型存在多个引擎: " + engine.type());
            }
        }
        this.engines = Map.copyOf(mapped);
    }

    public GisFileProcessingEngine require(GisProcessingType type) {
        GisFileProcessingEngine engine = engines.get(type);
        if (engine == null) {
            throw new IllegalStateException(type.displayName() + "引擎尚未接入或未启用");
        }
        return engine;
    }
}
