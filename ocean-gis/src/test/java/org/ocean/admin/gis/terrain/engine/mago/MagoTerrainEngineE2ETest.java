package org.ocean.admin.gis.terrain.engine.mago;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationRequest;
import org.ocean.admin.gis.terrain.engine.TerrainGenerationResult;
import org.ocean.admin.gis.terrain.engine.TerrainOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MagoTerrainEngineE2ETest {

    @Test
    void generatesCesiumTerrainFromRealDem() throws Exception {
        List<Path> inputs = inputPaths();
        assumeTrue(!inputs.isEmpty(),
                "Set -Dterrain.e2e.inputs=<GeoTIFFs> to run the terrain E2E test");

        Path output = outputPath().toAbsolutePath().normalize();
        Path temp = output.resolveSibling(output.getFileName() + "-temp");
        Path log = output.resolveSibling(output.getFileName() + ".log");

        MagoTerrainProperties properties = new MagoTerrainProperties();
        properties.setProcessTimeout(Duration.ofHours(2));
        MagoTerrainEngine engine = new MagoTerrainEngine(
                properties,
                new MagoCommandBuilder(),
                new MagoProcessRunner());
        TerrainOptions options = new TerrainOptions(
                0,
                null,
                "Ellipsoid",
                -10000D,
                TerrainOptions.InterpolationType.BILINEAR,
                null,
                null,
                true,
                false,
                false,
                true);

        TerrainGenerationResult result = engine.generate(
                new TerrainGenerationRequest(inputs, output, temp, log, options),
                System.out::println);

        assertEquals(0, result.exitCode());
        assertTrue(Files.isRegularFile(output.resolve("layer.json")), "layer.json must be generated");
        double[] bounds = MagoLayerJsonMetadata.readValidBounds(output);
        assertTrue(bounds[0] <= bounds[2], "west must not exceed east");
        assertTrue(bounds[1] <= bounds[3], "south must not exceed north");
        try (var files = Files.walk(output)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".terrain")),
                    "at least one terrain tile must be generated");
        }
    }

    private List<Path> inputPaths() {
        String value = System.getProperty(
                "terrain.e2e.inputs",
                System.getProperty("terrain.e2e.input", ""));
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    private Path outputPath() {
        return Path.of(System.getProperty(
                "terrain.e2e.output",
                "ocean-gis/target/e2e-terrain/n0e0"));
    }
}
