/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.geotiff;

import com.gaia3d.terrain.tile.generation.TerrainTilingContext;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.imagen.Interpolation;
import org.eclipse.imagen.PlanarImage;
import org.eclipse.imagen.RasterFactory;
import org.eclipse.imagen.TiledImage;
import org.eclipse.imagen.media.range.NoDataContainer;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.coverage.processing.Operation;
import org.geotools.api.parameter.ParameterValueGroup;
import org.geotools.api.referencing.ReferenceIdentifier;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.api.referencing.datum.Ellipsoid;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.coverage.processing.Operations;
import org.geotools.coverage.util.CoverageUtilities;
import org.geotools.gce.geotiff.GeoTiffReader;
import com.gaia3d.terrain.tile.raster.TerrainRasterFormat;
import com.gaia3d.terrain.tile.raster.TerrainRasterWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RasterStandardizer {
    public RasterStandardizer(TerrainTilingContext context) { this.context = context; }
    private static final int MAX_FILES_PER_DIRECTORY = 10_000;

    private final TerrainTilingContext context;

    public static String sourceDirectoryName(String sourceIdentifier) {
        String fileName = new File(sourceIdentifier).getName();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        String sanitized = sanitizePathSegment(baseName);
        String suffix = Integer.toHexString(sourceIdentifier.hashCode());
        return sanitized + "-" + suffix;
    }

    public static String sanitizePathSegment(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isBlank()) {
            return "raster";
        }
        return sanitized;
    }

    private static String shardDirectoryName(int itemIndex) {
        int shardIndex = Math.max(0, (itemIndex - 1) / MAX_FILES_PER_DIRECTORY);
        int shardStart = shardIndex * MAX_FILES_PER_DIRECTORY;
        int shardEnd = shardStart + MAX_FILES_PER_DIRECTORY - 1;
        return String.format("batch-%05d-%05d", shardStart, shardEnd);
    }

    public void standardize(GridCoverage2D source, File outputPath) {
        CoordinateReferenceSystem targetCRS = context.getOutputCRS();
        try {
            log.info("[Pre][Standardization] Splitting source raster into tiles... {}", outputPath.getName());
            int total = countTiles(source, context.getMaxRasterSize());
            log.info("[Pre][Standardization] Splitting completed. Total tiles: {}", total);
            AtomicInteger count = new AtomicInteger(0);
            processTileDescriptors(source, context.getMaxRasterSize(), tileDescriptor -> {
                context.checkCancelled();
                int current = count.incrementAndGet();
                log.info("[Pre][Standardization][{}/{}] Resampling tile {}", current, total, tileDescriptor.tileName());

                GridCoverage2D croppedTile = extractTileCoverage(source, tileDescriptor.gridEnvelope(), tileDescriptor.tileEnvelope());
                GridCoverage2D outputTile = croppedTile;
                try {
                    // 在重投影前过滤空块，避免背景填充值变成有效高程。
                    if (isAllNoData(croppedTile)) return;
                    CoordinateReferenceSystem sourceCRS = croppedTile.getCoordinateReferenceSystem();
                    if (!isSameCRS(sourceCRS, targetCRS)) {
                        outputTile = resample(croppedTile, targetCRS);
                    }

                    File tileFile = createOutputTileFile(outputPath, tileDescriptor.tileName(), current);
                    writeTerrainRaster(outputTile, tileFile);
                } finally {
                    disposeCoverage(outputTile);
                    if (outputTile != croppedTile) {
                        disposeCoverage(croppedTile);
                    }
                }
                log.info("[Pre][Standardization][{}/{}] Completed tile {}", current, total, tileDescriptor.tileName());
            });
        } catch (TransformException | IOException e) {
            log.error("Failed to standardization.", e);
            throw new RuntimeException(e);
        }
    }

    public void standardizeWithGeoid(GridCoverage2D source, File outputPath, File geoidFile) {

        GeoTiffReader reader = null;
        GridCoverage2D geoidCoverage = null;
        try (var input = new javax.imageio.stream.FileImageInputStream(geoidFile)) {
            reader = new GeoTiffReader(input);
            geoidCoverage = reader.read();

            CoordinateReferenceSystem targetCRS = context.getOutputCRS();
            try {
                final GridCoverage2D geoidCoverageRef = geoidCoverage;
                log.info("[Pre][Standardization][with Geoid] Splitting source raster into tiles... {}", outputPath.getName());
                int total = countTiles(source, context.getMaxRasterSize());
                log.info("[Pre][Standardization][with Geoid] Splitting completed. Total tiles: {}", total);
                AtomicInteger count = new AtomicInteger(0);

                processTileDescriptors(source, context.getMaxRasterSize(), tileDescriptor -> {
                    context.checkCancelled();
                    int current = count.incrementAndGet();
                    log.info("[Pre][Standardization][with Geoid][{}/{}] Resampling tile {}", current, total, tileDescriptor.tileName());

                    GridCoverage2D croppedTile = extractTileCoverage(source, tileDescriptor.gridEnvelope(), tileDescriptor.tileEnvelope());
                    GridCoverage2D resampledTile = croppedTile;
                    GridCoverage2D geoidAligned = null;
                    GridCoverage2D ellipsoidalDem = null;
                    try {
                        // 在高程改正前过滤空块，保留显式 NoData 的含义。
                        if (isAllNoData(croppedTile)) return;
                        CoordinateReferenceSystem sourceCRS = croppedTile.getCoordinateReferenceSystem();
                        if (!isSameCRS(sourceCRS, targetCRS)) {
                            GridCoverage2D lazyResampledTile = resample(croppedTile, targetCRS);
                            resampledTile = materializeCoverage(lazyResampledTile);
                            if (lazyResampledTile != resampledTile) {
                                disposeCoverage(lazyResampledTile);
                            }
                        }

                        GridGeometry2D demGrid = resampledTile.getGridGeometry();
                        GridCoverage2D lazyGeoidAligned = resampleGeoid(geoidCoverageRef, demGrid, demGrid.getCoordinateReferenceSystem());
                        geoidAligned = materializeCoverage(lazyGeoidAligned);
                        if (lazyGeoidAligned != geoidAligned) {
                            disposeCoverage(lazyGeoidAligned);
                        }
                        ellipsoidalDem = addGeoidPreserveDemNoData(resampledTile, geoidAligned);

                        File tileFile = createOutputTileFile(outputPath, tileDescriptor.tileName(), current);
                        writeTerrainRaster(ellipsoidalDem, tileFile);
                    } finally {
                        disposeCoverage(ellipsoidalDem);
                        disposeCoverage(geoidAligned);
                        disposeCoverage(resampledTile);
                        if (resampledTile != croppedTile) {
                            disposeCoverage(croppedTile);
                        }
                    }
                    log.info("[Pre][Standardization][with Geoid][{}/{}] Completed tile {}", current, total, tileDescriptor.tileName());
                });
            } catch (TransformException | IOException e) {
                throw new RuntimeException(e);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            disposeCoverage(geoidCoverage);
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Exception ex) {
                    log.error("Error:", ex);
                }
            }
        }
    }

    private void writeTerrainRaster(GridCoverage2D coverage, File geoTiffFile) throws IOException {
        if (isAllNoData(coverage)) {
            log.info("[Raster][I/O] Skip all-noData terrain raster: {}", geoTiffFile.getAbsolutePath());
            return;
        }
        String fileName = geoTiffFile.getName();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
        new TerrainRasterWriter(context).write(geoTiffFile.toPath().resolveSibling(baseName + TerrainRasterFormat.EXTENSION), coverage);
    }

    public RasterInfo cropTile(GridCoverage2D coverage, ReferencedEnvelope envelope, String tileName) {
        GridCoverage2D gridCoverage2D = crop(coverage, envelope);
        return new RasterInfo(tileName, gridCoverage2D);
    }

    public int countTiles(GridCoverage2D coverage, int tileSize) throws TransformException, IOException {
        AtomicInteger count = new AtomicInteger(0);
        processTileDescriptors(coverage, tileSize, tileDescriptor -> count.incrementAndGet());
        return count.get();
    }

    public void processTiles(GridCoverage2D coverage, int tileSize, TileProcessor processor) throws TransformException, IOException {
        processTileDescriptors(coverage, tileSize, tileDescriptor -> processor.process(tileDescriptor.tileName(), tileDescriptor.tileEnvelope()));
    }

    private void processTileDescriptors(GridCoverage2D coverage, int tileSize, TileDescriptorProcessor processor) throws TransformException, IOException {
        GridGeometry2D gridGeometry = coverage.getGridGeometry();
        GridEnvelope gridRange = gridGeometry.getGridRange();
        int width = gridRange.getSpan(0);
        int height = gridRange.getSpan(1);

        int margin = 4;
        int marginX = Math.max((int) (tileSize * 0.01), margin);
        int marginY = Math.max((int) (tileSize * 0.01), margin);
        for (int x = 0; x < width; x += tileSize) {
            for (int y = 0; y < height; y += tileSize) {
            context.checkCancelled();
                int xMax = Math.min(x + tileSize, width);
                int yMax = Math.min(y + tileSize, height);

                if ((x + tileSize) < width) {
                    xMax = Math.min(xMax + marginX, width);
                }
                if ((y + tileSize) < height) {
                    yMax = Math.min(yMax + marginY, height);
                }

                int xAux = x;
                if (xAux > marginX) {
                    xAux -= marginX;
                }

                int yAux = y;
                if (yAux > marginY) {
                    yAux -= marginY;
                }

                GridEnvelope2D gridEnvelope = new GridEnvelope2D(xAux, yAux, xMax - xAux, yMax - yAux);
                ReferencedEnvelope tileEnvelope = new ReferencedEnvelope(
                        gridGeometry.gridToWorld(gridEnvelope),
                        coverage.getCoordinateReferenceSystem()
                );
                String tileName = coverage.getName() + "-" + x / tileSize + "-" + y / tileSize;
                processor.process(new TileDescriptor(tileName, gridEnvelope, tileEnvelope));
            }
        }
    }

    public GridCoverage2D crop(GridCoverage2D coverage, ReferencedEnvelope envelope) {
        try {
            Operations ops = Operations.DEFAULT;
            return (GridCoverage2D) ops.crop(coverage, envelope);
        } catch (Exception e) {
            log.error("Failed to crop coverage : {}", coverage.getName());
            log.error("Error : ", e);
            throw new RuntimeException("Failed to crop coverage", e);
        }
    }

    public GridCoverage2D resample(GridCoverage2D sourceCoverage, CoordinateReferenceSystem targetCRS) {
        try {
            CoverageProcessor processor = CoverageProcessor.getInstance();

            Operation operation = processor.getOperation("Resample");
            ParameterValueGroup params = operation.getParameters();
            params.parameter("Source").setValue(sourceCoverage);
            params.parameter("CoordinateReferenceSystem").setValue(targetCRS);
            params.parameter("InterpolationType").setValue(Interpolation.getInstance(Interpolation.INTERP_NEAREST));

            NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(sourceCoverage);
            if (noDataContainer != null) {
                double[] backgroundValues = noDataContainer.getAsArray();
                params.parameter("BackgroundValues").setValue(backgroundValues);
            } else {
                double[] backgroundValues = new double[]{context.getNoDataValue()};
                params.parameter("BackgroundValues").setValue(backgroundValues);
            }
            return (GridCoverage2D) processor.doOperation(params);
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("高程栅格重投影失败: " + sourceCoverage.getName(), e);
        }
    }

    public GridCoverage2D resampleGeoid(GridCoverage2D sourceCoverage, GridGeometry2D gridGeometry, CoordinateReferenceSystem targetCRS) {
        try {
            CoverageProcessor processor = CoverageProcessor.getInstance();

            Operation operation = processor.getOperation("Resample");
            ParameterValueGroup params = operation.getParameters();
            params.parameter("Source").setValue(sourceCoverage);
            params.parameter("CoordinateReferenceSystem").setValue(targetCRS);
            params.parameter("GridGeometry").setValue(gridGeometry);
            params.parameter("InterpolationType").setValue(Interpolation.getInstance(Interpolation.INTERP_BILINEAR));

            NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(sourceCoverage);
            if (noDataContainer != null) {
                double[] backgroundValues = noDataContainer.getAsArray();
                params.parameter("BackgroundValues").setValue(backgroundValues);
            } else {
                double[] backgroundValues = new double[]{context.getNoDataValue()};
                params.parameter("BackgroundValues").setValue(backgroundValues);
            }
            return (GridCoverage2D) processor.doOperation(params);
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
            throw new IllegalStateException("高程栅格重投影失败: " + sourceCoverage.getName(), e);
        }
    }

    public Double getNodata(GridCoverage2D coverage) {
        NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(coverage);
        if (noDataContainer != null) {
            double[] noDataValues = noDataContainer.getAsArray();
            return noDataValues[0];
        } else {
            return null;
        }
    }

    public GridCoverage2D addGeoidPreserveDemNoData(GridCoverage2D dem, GridCoverage2D alignedGeoid) {
        double globalNodata = context.getNoDataValue();

        RenderedImage demImg = dem.getRenderedImage();
        RenderedImage geoidImg = alignedGeoid.getRenderedImage();

        Double demNoDataVal = getNodata(dem);
        boolean hasDemNoDataVal = demNoDataVal != null;
        double demNoData = hasDemNoDataVal ? demNoDataVal : Double.NaN;
        double outputNoData = hasDemNoDataVal ? demNoData : globalNodata;

        Raster demRaster = demImg.getData();
        Raster geoRaster = geoidImg.getData();

        Rectangle demRectangle = demRaster.getBounds();
        Rectangle geoidRectangle = geoRaster.getBounds();
        Rectangle intersection = demRectangle.intersection(geoidRectangle);

        WritableRaster outRaster = RasterFactory.createBandedRaster(DataBuffer.TYPE_FLOAT, demRectangle.width, demRectangle.height, 1, null);

        for (int y = demRectangle.y; y < demRectangle.y + demRectangle.height; y++) {
            context.checkCancelled();
            for (int x = demRectangle.x; x < demRectangle.x + demRectangle.width; x++) {
                int outputX = x - demRectangle.x;
                int outputY = y - demRectangle.y;
                outRaster.setSample(outputX, outputY, 0, (float) demRaster.getSampleDouble(x, y, 0));
            }
        }

        for (int y = intersection.y; y < intersection.y + intersection.height; y++) {
            context.checkCancelled();
            for (int x = intersection.x; x < intersection.x + intersection.width; x++) {
                int outputX = x - demRectangle.x;
                int outputY = y - demRectangle.y;

                double H = demRaster.getSampleDouble(x, y, 0);
                if (isNoDataValue(H, demNoData)) {
                    outRaster.setSample(outputX, outputY, 0, (float) outputNoData);
                } else {
                    double N = geoRaster.getSampleDouble(x, y, 0);
                    outRaster.setSample(outputX, outputY, 0, (float) (H + N));
                }
            }
        }

        demRaster = null;
        geoRaster = null;

        return createCoveragePreservingNoData(dem, outRaster, dem.getEnvelope());
    }

    private void disposeCoverage(GridCoverage2D coverage) {
        if (coverage != null) {
            coverage.dispose(true);
        }
    }

    private boolean isAllNoData(GridCoverage2D coverage) {
        Raster raster = coverage.getRenderedImage().getData();
        double noDataValue = resolveNoDataValue(coverage);
        return isAllNoData(raster, noDataValue);
    }

    private boolean isAllNoData(Raster raster, double noDataValue) {
        int minX = raster.getMinX();
        int minY = raster.getMinY();
        int maxX = minX + raster.getWidth();
        int maxY = minY + raster.getHeight();
        for (int y = minY; y < maxY; y++) {
            context.checkCancelled();
            for (int x = minX; x < maxX; x++) {
                double value = raster.getSampleDouble(x, y, 0);
                if (!isNoDataValue(value, noDataValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    private double resolveNoDataValue(GridCoverage2D coverage) {
        NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(coverage);
        return noDataContainer != null ? noDataContainer.getAsSingleValue() : context.getNoDataValue();
    }

    private GridCoverage2D materializeCoverage(GridCoverage2D coverage) {
        RenderedImage renderedImage = coverage.getRenderedImage();
        if (renderedImage instanceof WritableRenderedImage) {
            return coverage;
        }

        Raster sourceRaster = renderedImage.getData();
        WritableRaster materializedRaster = createZeroBasedWritableRaster(sourceRaster);
        return createCoveragePreservingNoData(coverage, materializedRaster, coverage.getEnvelope());
    }

    private boolean isNoDataValue(double value, double noDataValue) {
        return !Double.isFinite(value) || Double.compare(value, noDataValue) == 0
                || Double.compare(value, context.getNoDataValue()) == 0;
    }

    private GridCoverage2D extractTileCoverage(GridCoverage2D sourceCoverage, GridEnvelope2D gridEnvelope, ReferencedEnvelope tileEnvelope) {
        Raster sourceRaster = sourceCoverage.getRenderedImage().getData(gridEnvelope);
        WritableRaster zeroBasedRaster = createSharedZeroBasedWritableRaster(sourceRaster);
        return createCoveragePreservingNoData(sourceCoverage, zeroBasedRaster, tileEnvelope);
    }

    private GridCoverage2D createCoveragePreservingNoData(GridCoverage2D sourceCoverage, WritableRaster raster, org.geotools.api.geometry.Bounds envelope) {
        if (CoverageUtilities.getNoDataProperty(sourceCoverage) == null) {
            return new GridCoverageFactory().create(sourceCoverage.getName(), raster, envelope);
        }

        TiledImage image = new TiledImage(
                raster.getMinX(),
                raster.getMinY(),
                raster.getWidth(),
                raster.getHeight(),
                raster.getMinX(),
                raster.getMinY(),
                raster.getSampleModel(),
                createColorModel(raster.getSampleModel())
        );
        image.setData(raster);
        return new GridCoverageFactory().create(
                sourceCoverage.getName(),
                image,
                envelope,
                null,
                null,
                createNoDataProperties(sourceCoverage)
        );
    }

    private Map<String, Object> createNoDataProperties(GridCoverage2D sourceCoverage) {
        Map<String, Object> properties = new HashMap<>();
        NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(sourceCoverage);
        if (noDataContainer != null) {
            CoverageUtilities.setNoDataProperty(properties, noDataContainer);
        }
        return properties;
    }

    private ColorModel createColorModel(SampleModel sampleModel) {
        ColorModel colorModel = PlanarImage.createColorModel(sampleModel);
        if (colorModel != null) {
            return colorModel;
        }

        int numBands = sampleModel.getNumBands();
        if (numBands == 1) {
            return new ComponentColorModel(
                    ColorSpace.getInstance(ColorSpace.CS_GRAY),
                    sampleModel.getSampleSize(),
                    false,
                    false,
                    Transparency.OPAQUE,
                    sampleModel.getDataType()
            );
        }

        if (numBands == 3 || numBands == 4) {
            boolean hasAlpha = numBands == 4;
            return new ComponentColorModel(
                    ColorSpace.getInstance(ColorSpace.CS_sRGB),
                    sampleModel.getSampleSize(),
                    hasAlpha,
                    false,
                    hasAlpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                    sampleModel.getDataType()
            );
        }

        throw new IllegalArgumentException("Unsupported sample model for GeoTIFF color model: bands=" + numBands);
    }

    private WritableRaster createZeroBasedWritableRaster(Raster sourceRaster) {
        Raster translatedRaster = sourceRaster.createTranslatedChild(0, 0);
        WritableRaster materializedRaster = translatedRaster.createCompatibleWritableRaster(translatedRaster.getWidth(), translatedRaster.getHeight());
        materializedRaster.setRect(translatedRaster);
        return materializedRaster;
    }

    WritableRaster createSharedZeroBasedWritableRaster(Raster sourceRaster) {
        if (sourceRaster instanceof WritableRaster writableRaster) {
            if (writableRaster.getMinX() == 0 && writableRaster.getMinY() == 0) {
                return writableRaster;
            }
            return writableRaster.createWritableTranslatedChild(0, 0);
        }
        return createZeroBasedWritableRaster(sourceRaster);
    }

    private File createOutputTileFile(File sourceOutputDirectory, String tileName, int tileIndex) {
        File shardDirectory = new File(sourceOutputDirectory, shardDirectoryName(tileIndex));
        if (!shardDirectory.exists() && !shardDirectory.mkdirs()) {
            throw new RuntimeException("Failed to create shard directory: " + shardDirectory.getAbsolutePath());
        }
        String uniqueTileName = tileName + "-" + UUID.randomUUID();
        return new File(shardDirectory, uniqueTileName + TerrainRasterFormat.EXTENSION);
    }

    public boolean isSameCRS(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) {

        Iterator<ReferenceIdentifier> sourceCRSIterator = sourceCRS.getIdentifiers().iterator();
        Iterator<ReferenceIdentifier> targetCRSIterator = targetCRS.getIdentifiers().iterator();

        if (sourceCRSIterator.hasNext() && targetCRSIterator.hasNext()) {
            String sourceCRSCode = sourceCRSIterator.next().getCode();
            String targetCRSCode = targetCRSIterator.next().getCode();
            if (sourceCRSCode.equals(targetCRSCode)) {
                return true;
            }
        }

        if (CRS.equalsIgnoreMetadata(sourceCRS, targetCRS)) {
            return true;
        }

        if (!sourceCRSIterator.hasNext() && sourceCRS instanceof GeographicCRS && targetCRS instanceof GeographicCRS) {
            Ellipsoid sourceEllipsoid = ((GeographicCRS) sourceCRS).getDatum().getEllipsoid();
            Ellipsoid targetEllipsoid = ((GeographicCRS) targetCRS).getDatum().getEllipsoid();
            double sourceSemiMajor = sourceEllipsoid.getSemiMajorAxis();
            double targetSemiMajor = targetEllipsoid.getSemiMajorAxis();
            double sourceSemiMinor = sourceEllipsoid.getSemiMinorAxis();
            double targetSemiMinor = targetEllipsoid.getSemiMinorAxis();
            return Math.abs(sourceSemiMajor - targetSemiMajor) < 1.0 && Math.abs(sourceSemiMinor - targetSemiMinor) < 1.0;
        }
        return false;
    }

    @FunctionalInterface
    interface TileProcessor {
        void process(String tileName, ReferencedEnvelope tileEnvelope) throws TransformException, IOException;
    }

    @FunctionalInterface
    private interface TileDescriptorProcessor {
        void process(TileDescriptor tileDescriptor) throws TransformException, IOException;
    }

    private record TileDescriptor(String tileName, GridEnvelope2D gridEnvelope, ReferencedEnvelope tileEnvelope) {}

}
