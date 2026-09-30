package org.ocean.admin.gis.processing.config;

import org.ocean.admin.gis.imagery.GeoTiffTileGenerator;
import org.ocean.admin.gis.imagery.GeoTiffOverviewBuilder;
import org.ocean.admin.gis.imagery.GeoTiffPreprocessor;
import org.ocean.admin.gis.processing.engine.GisFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.ImageryFileProcessingEngine;
import org.ocean.admin.gis.processing.engine.TerrainFileProcessingEngine;
import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 文件处理引擎适配层装配。 */
@Configuration(proxyBeanMethods = false)
public class GisFileProcessingEngineConfiguration {

    @Bean
    public GeoTiffTileGenerator geoTiffTileGenerator(ObjectMapper objectMapper) {
        return new GeoTiffTileGenerator(objectMapper);
    }

    @Bean
    public GeoTiffOverviewBuilder geoTiffOverviewBuilder() {
        return new GeoTiffOverviewBuilder();
    }

    @Bean
    public GeoTiffPreprocessor geoTiffPreprocessor(
            GisProcessingProperties properties, GeoTiffOverviewBuilder overviewBuilder) {
        return new GeoTiffPreprocessor(properties, overviewBuilder);
    }

    @Bean
    public GisFileProcessingEngine imageryFileProcessingEngine(
            GeoTiffTileGenerator tileGenerator, GeoTiffPreprocessor preprocessor) {
        return new ImageryFileProcessingEngine(tileGenerator, preprocessor);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "gis.terrain.mago",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public GisFileProcessingEngine terrainFileProcessingEngine(TerrainEngine terrainEngine) {
        return new TerrainFileProcessingEngine(terrainEngine);
    }
}
