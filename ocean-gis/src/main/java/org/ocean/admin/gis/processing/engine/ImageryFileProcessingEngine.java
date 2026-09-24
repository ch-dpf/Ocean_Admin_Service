package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.dto.GisProcessingParameters;
import org.ocean.admin.gis.imagery.GeoTiffTileGenerator;
import org.ocean.admin.gis.imagery.ImageryTileOptions;
import org.ocean.admin.gis.processing.GisProcessingProgress;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** 将通用 GIS 任务适配为纯 Java GeoTIFF 静态影像切片。 */
public class ImageryFileProcessingEngine implements GisFileProcessingEngine {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("tif", "tiff");
    private final GeoTiffTileGenerator tileGenerator;

    public ImageryFileProcessingEngine(GeoTiffTileGenerator tileGenerator) {
        this.tileGenerator = tileGenerator;
    }

    @Override
    public GisProcessingType type() {
        return GisProcessingType.IMAGERY;
    }

    @Override
    public void validate(String originalName, String extension, Path inputPath) {
        extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("影像切片仅支持 tif/tiff，当前文件: "
                    + originalName);
        }
    }

    @Override
    public void process(List<Path> inputPaths, GisProcessingWorkspace workspace,
            GisProcessingParameters parameters, Consumer<GisProcessingProgress> progressListener) {
        if (inputPaths == null || inputPaths.size() != 1) {
            throw new IllegalArgumentException("首期影像切片每个任务仅支持一个 GeoTIFF");
        }
        tileGenerator.generate(inputPaths.get(0), workspace.outputPath(),
                ImageryTileOptions.from(parameters), progressListener);
    }
}
