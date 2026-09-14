package org.ocean.admin.gis.processing.engine;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.processing.GisProcessingExecution;
import org.ocean.admin.gis.processing.GisProcessingType;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GisFileProcessingEngineRegistryTest {

    @Test
    void routesByProcessingTypeAndRejectsUnavailableEngine() {
        GisFileProcessingEngine terrain = engine(GisProcessingType.TERRAIN);
        GisFileProcessingEngineRegistry registry =
                new GisFileProcessingEngineRegistry(List.of(terrain));

        assertSame(terrain, registry.require(GisProcessingType.TERRAIN));
        assertThrows(IllegalStateException.class,
                () -> registry.require(GisProcessingType.IMAGERY));
    }

    @Test
    void rejectsDuplicateEngineForOneType() {
        assertThrows(IllegalStateException.class,
                () -> new GisFileProcessingEngineRegistry(List.of(
                        engine(GisProcessingType.TERRAIN),
                        engine(GisProcessingType.TERRAIN))));
    }

    private GisFileProcessingEngine engine(GisProcessingType type) {
        return new GisFileProcessingEngine() {
            @Override
            public GisProcessingType type() {
                return type;
            }

            @Override
            public void validate(GisFileMeta fileMeta, Path inputPath) {
            }

            @Override
            public void process(GisProcessingExecution execution, Consumer<String> outputListener) {
            }
        };
    }
}
