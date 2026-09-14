package org.ocean.admin.gis.terrain.engine.mago;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MagoCommandBuilderTest {

    private final Path tempDir = Path.of("target", "test-work", "mago-command").toAbsolutePath();

    @Test
    void buildsArgumentListForMultipleInputsWithoutShellConcatenation() {
        MagoTerrainProperties properties = new MagoTerrainProperties();
        properties.setJavaCommand("java");
        properties.setJarPath(tempDir.resolve("mago.jar"));
        properties.setMaxHeap("4g");
        properties.setProcessTimeout(Duration.ofHours(1));

        TerrainOptions options = new TerrainOptions(
                0,
                14,
                "EGM96",
                -9999D,
                TerrainOptions.InterpolationType.NEAREST,
                8192,
                32,
                false,
                true,
                true,
                true);
        TerrainGenerationRequest request = new TerrainGenerationRequest(
                List.of(tempDir.resolve("dem a.tif"), tempDir.resolve("dem-b.tif")),
                tempDir.resolve("output"),
                tempDir.resolve("work"),
                tempDir.resolve("logs/mago.log"),
                options);

        List<String> command = new MagoCommandBuilder().build(properties, request);

        assertThat(command)
                .startsWith("java", "-Xmx4g", "-jar", properties.getJarPath().toAbsolutePath().toString())
                .containsSubsequence("--input", tempDir.resolve("dem a.tif").toAbsolutePath().toString())
                .containsSubsequence("--input", tempDir.resolve("dem-b.tif").toAbsolutePath().toString())
                .containsSubsequence("--output", tempDir.resolve("output").toAbsolutePath().toString())
                .containsSubsequence("--maxDepth", "14")
                .containsSubsequence("--geoid", "EGM96")
                .contains("--noCalculateNormals", "--continue", "--leaveTemp")
                .doesNotContain("--json");

        List<String> layerJsonCommand = new MagoCommandBuilder().buildLayerJson(properties, request);
        assertThat(layerJsonCommand)
                .containsSubsequence("--input", tempDir.resolve("output").toAbsolutePath().toString())
                .containsSubsequence("--output", tempDir.resolve("output").toAbsolutePath().toString())
                .contains("--json", "--noCalculateNormals")
                .doesNotContain(tempDir.resolve("dem a.tif").toAbsolutePath().toString());
    }
}
