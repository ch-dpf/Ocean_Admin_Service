package org.ocean.admin.gis.processing.input;

import org.ocean.admin.gis.processing.GisInputSourceType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按输入来源选择解析器。 */
@Component
public class GisProcessingInputResolverRegistry {
    private final Map<GisInputSourceType, GisProcessingInputResolver> resolvers;

    public GisProcessingInputResolverRegistry(List<GisProcessingInputResolver> candidates) {
        EnumMap<GisInputSourceType, GisProcessingInputResolver> mapped =
                new EnumMap<>(GisInputSourceType.class);
        for (GisProcessingInputResolver resolver : candidates) {
            if (mapped.putIfAbsent(resolver.type(), resolver) != null) {
                throw new IllegalStateException("输入来源存在多个解析器: " + resolver.type());
            }
        }
        this.resolvers = Map.copyOf(mapped);
    }

    public GisProcessingInputResolver require(GisInputSourceType type) {
        GisProcessingInputResolver resolver = resolvers.get(type);
        if (resolver == null) {
            throw new IllegalStateException("输入来源解析器未启用: " + type);
        }
        return resolver;
    }
}
