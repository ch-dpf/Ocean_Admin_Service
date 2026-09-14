package org.ocean.admin.gis.terrain.engine.mago;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MagoLayerJsonMetadataTest {

    private final Path tempDir = Path.of("target", "test-work", "mago-layer-json");

    @Test
    void restoresSourceBoundsWithoutReplacingAvailableTiles() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("layer.json"),
                "{\"bounds\":[0,-5,5,0],\"available\":[[{\"startX\":0,\"endX\":2}]]}");
        double[] sourceBounds = MagoLayerJsonMetadata.readValidBounds(tempDir);
        Files.writeString(tempDir.resolve("layer.json"),
                "{\"bounds\":[-1,1,6,-6],\"available\":[[{\"startX\":0,\"endX\":1}]]}");

        MagoLayerJsonMetadata.restoreBounds(tempDir, sourceBounds);

        assertThat(Files.readString(tempDir.resolve("layer.json")))
                .contains("\"bounds\":[0.0,-5.0,5.0,0.0]")
                .contains("\"endX\":1");
    }
}
