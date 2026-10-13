package org.ocean.admin.gis.terrain.config;

import org.ocean.admin.gis.terrain.engine.TerrainEngine;
import org.ocean.admin.gis.terrain.engine.mago.MagoCommandBuilder;
import org.ocean.admin.gis.terrain.engine.mago.MagoProcessRunner;
import org.ocean.admin.gis.terrain.engine.mago.MagoTerrainEngine;
import org.ocean.admin.gis.terrain.engine.mago.MagoTerrainProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 地形引擎装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MagoTerrainProperties.class)
public class TerrainEngineConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "gis.terrain.mago",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TerrainEngine magoTerrainEngine(
            MagoTerrainProperties properties,
            ObjectMapper objectMapper) {
        return new MagoTerrainEngine(
                properties,
                new MagoCommandBuilder(),
                new MagoProcessRunner(),
                objectMapper);
    }
}
