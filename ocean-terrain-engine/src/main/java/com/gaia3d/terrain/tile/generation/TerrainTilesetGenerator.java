/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.generation;

import com.gaia3d.terrain.structure.GeographicExtension;
import com.gaia3d.terrain.structure.TerrainTriangle;
import com.gaia3d.terrain.tile.core.GeographicTerrainTile;
import com.gaia3d.terrain.tile.core.TileIndices;
import com.gaia3d.terrain.tile.core.TileRange;
import com.gaia3d.terrain.tile.custom.AvailableTileSet;
import com.gaia3d.terrain.tile.elevation.TerrainElevationData;
import com.gaia3d.terrain.tile.elevation.TerrainElevationModeler;
import com.gaia3d.terrain.tile.geotiff.GeoTiffCoverageStore;
import com.gaia3d.terrain.tile.layer.TerrainLayer;
import com.gaia3d.terrain.tile.mesh.TileMatrix;
import com.gaia3d.terrain.util.GeographicTerrainTileUtils;
import com.gaia3d.util.DecimalUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.TransformException;
import org.joml.Vector2d;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
@Slf4j
public class TerrainTilesetGenerator implements TerrainTileGenerator {
    private final TerrainTilingContext context;

    private final int rasterTileSize = 256;
    private final String imaginaryType = "CRS84";

    private final Map<Integer, List<File>> depthGeoTiffFilesMap;
    private final Map<Integer, Double> depthDesiredPixelSizeXinMetersMap;
    private final Map<Integer, Double> depthMaxDiffBetweenGeoTiffSampleAndTrianglePlaneMap = new HashMap<>();
    private final List<GeographicTerrainTile> geographicTerrainTileList = new ArrayList<>();

    private final TerrainRasterPreprocessor rasterPreprocessor;
    private final TerrainTileStore terrainTileStore;

    private TerrainElevationModeler terrainElevationModeler = null;
    private int geoTiffFilesCount = 0;
    private double vertexCoincidentError = 1e-11;

    private int triangleRefinementMaxIterations = 20;
    private TerrainLayer terrainLayer = null;
    private boolean originIsLeftUp = false;
    private List<Double> maxTriangleSizeForTileDepthList = new ArrayList<>();
    private List<Double> minTriangleSizeForTileDepthList = new ArrayList<>();

    private List<TerrainElevationData> terrainElevationDataList = new ArrayList<>();
    private List<TerrainTriangle> triangleList = new ArrayList<>();
    private Vector2d pixelSizeDegrees = new Vector2d();
    private Map<String, String> mapNoUsableGeotiffPaths;
    private GeoTiffCoverageStore geoTiffCoverageStore;

    private List<File> standardizedGeoTiffFiles;

    private AvailableTileSet availableTileSet;

    public TerrainTilesetGenerator(TerrainTilingContext context) {
        this.context = context;
        this.depthGeoTiffFilesMap = context.getDepthGeoTiffFilesMap();
        this.depthDesiredPixelSizeXinMetersMap = context.getDepthDesiredPixelSizeXinMetersMap();
        this.rasterPreprocessor = new TerrainRasterPreprocessor(context);
        this.terrainTileStore = new TerrainTileStore(context, this);
        this.mapNoUsableGeotiffPaths = context.getMapNoUsableGeotiffPaths();
        this.geoTiffCoverageStore = context.getGeoTiffCoverageStore();
        this.standardizedGeoTiffFiles = context.getStandardizedGeoTiffFiles();
        this.availableTileSet = context.getAvailableTileSet();

        double intensity = context.getIntensity();

        for (int i = 0; i < 28; i++) {
            context.checkCancelled();
            double tileSizeMeters = GeographicTerrainTileUtils.getTileSizeInMetersByDepth(i);
            double maxSize = tileSizeMeters / 2.5;
            if (i < 11) {
                maxSize *= 0.2;
            }

            maxTriangleSizeForTileDepthList.add(maxSize);

            double minSize = tileSizeMeters * 0.1 / (intensity);

            if (i > 17) {
                minSize *= 0.75;
            } else if (i > 15) {
                minSize *= 1.0;
            } else if (i > 14) {
                minSize *= 1.125;
            } else if (i > 13) {
                minSize *= 1.25;
            } else if (i > 12) {
                minSize *= 1.25;
            } else if (i > 10) {
                minSize *= 1.25;
            } else {
                minSize *= 1.0;
            }
            minTriangleSizeForTileDepthList.add(minSize);
        }

        for (int depth = 0; depth <= 28; depth++) {
            context.checkCancelled();
            double tileSizeMeters = GeographicTerrainTileUtils.getTileSizeInMetersByDepth(depth);
            double desiredPixelSizeXinMeters = tileSizeMeters / 256.0;
            this.depthDesiredPixelSizeXinMetersMap.put(depth, desiredPixelSizeXinMeters);
        }
    }

    /** 公共预处理完成后执行当前格式的生成流程。 */
    @Override public void generate() throws IOException, FactoryException, TransformException {
        this.terrainElevationModeler = new TerrainElevationModeler(context);
        terrainElevationModeler.setTerrainTilesetGenerator(this);
        terrainElevationModeler.setTerrainElevationDataFiles(resolveRasterFilesForDepth(0));
        terrainElevationModeler.generateTerrainQuadTree(0);
        if (context.isContinue()) continueAvailableTileMeshes();
        else generateAvailableTileMeshes();
        context.checkCancelled();
        if (context.getRequest().options().generateLayerJson()) {
            terrainLayer.saveFinalJson(java.nio.file.Path.of(context.getOutputPath()), context);
        }
    }

    @Override public void close() { deleteObjects(); }

    public void setOriginIsLeftUp(boolean originIsLeftUp) {
        this.originIsLeftUp = originIsLeftUp;
        this.context.setOriginIsLeftUp(originIsLeftUp);
    }

    public void deleteObjects() {
        if (this.terrainElevationModeler != null) {
            this.terrainElevationModeler.deleteObjects();
            this.terrainElevationModeler = null;
        }

        if (this.terrainLayer != null) {
            this.terrainLayer = null;
        }

        if (this.terrainElevationDataList != null) {
            this.terrainElevationDataList.clear();
        }

        if (this.triangleList != null) {
            this.triangleList.clear();
        }

        this.geographicTerrainTileList.clear();
        this.standardizedGeoTiffFiles.clear();
        this.geoTiffCoverageStore.clear();

        this.depthGeoTiffFilesMap.clear();
        this.depthDesiredPixelSizeXinMetersMap.clear();
        this.depthMaxDiffBetweenGeoTiffSampleAndTrianglePlaneMap.clear();
        this.maxTriangleSizeForTileDepthList.clear();
        this.minTriangleSizeForTileDepthList.clear();
        this.mapNoUsableGeotiffPaths.clear();
        this.availableTileSet.getMapDepthAvailableTileRanges().clear();
    }

    public void makeTempFilesFromQuantizedMeshes(int depth) {
        terrainTileStore.writeTempTilesFromQuantizedMeshes(depth);
    }

    public void makeTempFilesFromQuantizedMeshes(int depth, TileRange tileRange) {
        terrainTileStore.writeTempTilesFromQuantizedMeshes(depth, tileRange);
    }

    private void makeChildrenTempFiles(int depth) {
        terrainTileStore.writeChildTempTiles(depth);
    }

    private int determineExistentTileSetMaxDepth(String tileSetDirectory) {
        return terrainTileStore.determineExistentTileSetMaxDepth(tileSetDirectory);
    }

    private boolean existTempFiles(int depth) {
        return terrainTileStore.existTempFiles(depth);
    }

    public void continueAvailableTileMeshes() throws IOException, TransformException, FactoryException {
        String outputDirectory = context.getOutputPath();
        int existentMaxDepth = determineExistentTileSetMaxDepth(outputDirectory);
        log.info("existent max depth: {}", existentMaxDepth);

        GeographicExtension geographicExtension = this.terrainElevationModeler.getRootGeographicExtension();

        double minLon = geographicExtension.getMinLongitudeDeg();
        double maxLon = geographicExtension.getMaxLongitudeDeg();
        double minLat = geographicExtension.getMinLatitudeDeg();
        double maxLat = geographicExtension.getMaxLatitudeDeg();

        terrainLayer = new TerrainLayer(context.getObjectMapper());
        double[] bounds = terrainLayer.getBounds();
        bounds[0] = minLon;
        bounds[1] = minLat;
        bounds[2] = maxLon;
        bounds[3] = maxLat;

        if (context.isCalculateNormalsExtension()) {
            terrainLayer.addExtension("octvertexnormals");
        }
        if (context.isWaterMaskExtension()) {
            terrainLayer.addExtension("watermask");
        }
        if (context.isMetaDataExtension()) {
            terrainLayer.addExtension("metadata");
        }

        log.info("----------------------------------------");
        int minTileDepth = 0;
        int maxTileDepth = context.getMaximumTileDepth();

        int availableMaxDepth = this.availableTileSet.getMaxAvailableDepth();
        if (availableMaxDepth < maxTileDepth) {
            maxTileDepth = availableMaxDepth;
        }

        this.availableTileSet.deleteTileRangesOverDepth(maxTileDepth);

        // 从已有的最深层重算，补齐取消时可能只写了一部分的层级。
        minTileDepth = Math.max(minTileDepth, existentMaxDepth);
        saveLayerJsonBeforeTileGeneration();

        for (int depth = minTileDepth; depth <= maxTileDepth; depth += 1) {
            context.checkCancelled();
            long startTime = System.currentTimeMillis();

            if (depth > 0 && !existTempFiles(depth)) {
                log.info("making tempFiles from quantized meshes... depth: {}", depth - 1);
                makeTempFilesFromQuantizedMeshes(depth - 1);
                makeChildrenTempFiles(depth - 1);
            }

            this.triangleRefinementMaxIterations = GeographicTerrainTileUtils.getRefinementIterations(depth);
            this.terrainElevationModeler.deleteObjects();
            this.terrainElevationModeler = new TerrainElevationModeler(context);
            this.terrainElevationModeler.setTerrainTilesetGenerator(this);
            this.terrainElevationModeler.setTerrainElevationDataFiles(resolveRasterFilesForDepth(depth));
            this.terrainElevationModeler.generateTerrainQuadTree(depth);
            int mosaicSize = context.getMosaicSize();

            List<TileRange> availableTileRangesAtDepth = this.availableTileSet.getAvailableTileRangesAtDepth(depth);
            List<TileRange> subDividedTilesRanges = new ArrayList<>();

            for (TileRange availableTileRange : availableTileRangesAtDepth) {
                GeographicTerrainTileUtils.subDivideTileRange(availableTileRange, mosaicSize, mosaicSize, subDividedTilesRanges);
            }

            log.info("[Tile][{}/{}] Start generating tile meshes - Divided Tiles Size: {}", depth, maxTileDepth, subDividedTilesRanges.size());
            AtomicInteger counter = new AtomicInteger(0);

            int total = subDividedTilesRanges.size();
            for (TileRange subDividedTilesRange : subDividedTilesRanges) {
                int progress = counter.incrementAndGet();
                log.info("[Tile][{}/{}][{}/{}] generate wgs84 raster all tiles...", depth, maxTileDepth, progress, total);
                TileRange expandedTilesRange = subDividedTilesRange.expand1();
                this.terrainElevationModeler.prepareTileRastersForRange(expandedTilesRange, this);

                log.info("[Tile][{}/{}][{}/{}] process tiling...", depth, maxTileDepth, progress, total);
                TileMatrix tileMatrix = new TileMatrix(subDividedTilesRange, this);

                boolean isFirstGeneration = (depth == 0);
                tileMatrix.makeMatrixMesh(isFirstGeneration);
                tileMatrix.deleteObjects();
            }

            if (!context.isLeaveTemp()) {
                this.deleteTempFilesByDepth(depth);
            }

            this.terrainElevationModeler.deleteGeoTiffManager();
            this.terrainElevationModeler.deleteTileRaster();
            this.terrainElevationModeler.deleteCoverage();

            long endTime = System.currentTimeMillis();
            log.info("[Tile][{}/{}] - End making tile meshes : Duration: {}", depth, maxTileDepth, DecimalUtils.millisecondToDisplayTime(endTime - startTime));

            String javaHeapSize = System.getProperty("java.vm.name") + " " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB";

            String maxMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().maxMemory());

            String totalMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().totalMemory());

            String freeMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().freeMemory());

            String usedMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            log.debug("[Tile][{}/{}] Java Heap Size: {} - MaxMem: {}MB / TotalMem: {}MB / FreeMem: {}MB / UsedMem: {}MB ({}%)", depth, maxTileDepth, javaHeapSize, maxMem, totalMem, freeMem, usedMem);
            log.info("----------------------------------------");
        }
        terrainLayer.saveJsonFileCustom(context.getOutputPath(), "layer.json", this.availableTileSet);
    }

    public void generateAvailableTileMeshes() throws IOException, TransformException, FactoryException {
        GeographicExtension geographicExtension = this.terrainElevationModeler.getRootGeographicExtension();

        double minLon = geographicExtension.getMinLongitudeDeg();
        double maxLon = geographicExtension.getMaxLongitudeDeg();
        double minLat = geographicExtension.getMinLatitudeDeg();
        double maxLat = geographicExtension.getMaxLatitudeDeg();

        terrainLayer = new TerrainLayer(context.getObjectMapper());
        double[] bounds = terrainLayer.getBounds();
        bounds[0] = minLon;
        bounds[1] = minLat;
        bounds[2] = maxLon;
        bounds[3] = maxLat;

        if (context.isCalculateNormalsExtension()) {
            terrainLayer.addExtension("octvertexnormals");
        }
        if (context.isWaterMaskExtension()) {
            terrainLayer.addExtension("watermask");
        }
        if (context.isMetaDataExtension()) {
            terrainLayer.addExtension("metadata");
        }

        log.info("----------------------------------------");
        int minTileDepth = 0;
        int maxTileDepth = context.getMaximumTileDepth();
        int availableMaxDepth = this.availableTileSet.getMaxAvailableDepth();
        if (maxTileDepth < 0) {
            maxTileDepth = availableMaxDepth;
        } else if (availableMaxDepth < maxTileDepth) {
            maxTileDepth = availableMaxDepth;
        }

        this.availableTileSet.deleteTileRangesOverDepth(maxTileDepth);
        saveLayerJsonBeforeTileGeneration();

        for (int depth = minTileDepth; depth <= maxTileDepth; depth += 1) {
            context.checkCancelled();
            long startTime = System.currentTimeMillis();

            this.triangleRefinementMaxIterations = GeographicTerrainTileUtils.getRefinementIterations(depth);
            this.terrainElevationModeler.deleteObjects();
            this.terrainElevationModeler = new TerrainElevationModeler(context);
            this.terrainElevationModeler.setTerrainTilesetGenerator(this);
            this.terrainElevationModeler.setTerrainElevationDataFiles(resolveRasterFilesForDepth(depth));
            this.terrainElevationModeler.generateTerrainQuadTree(depth);
            int mosaicSize = context.getMosaicSize();

            List<TileRange> availableTileRangesAtDepth = this.availableTileSet.getAvailableTileRangesAtDepth(depth);
            List<TileRange> subDividedTilesRanges = new ArrayList<>();

            for (TileRange availableTileRange : availableTileRangesAtDepth) {
                GeographicTerrainTileUtils.subDivideTileRange(availableTileRange, mosaicSize, mosaicSize, subDividedTilesRanges);
            }

            log.info("[Tile][{}/{}] Start generating tile meshes - Divided Tiles Size: {}", depth, maxTileDepth, subDividedTilesRanges.size());
            AtomicInteger counter = new AtomicInteger(0);

            int total = subDividedTilesRanges.size();
            for (TileRange subDividedTilesRange : subDividedTilesRanges) {
                int progress = counter.incrementAndGet();
                log.info("[Tile][{}/{}][{}/{}] generate raster tiles...", depth, maxTileDepth, progress, total);
                TileRange expandedTilesRange = subDividedTilesRange.expand1();
                this.terrainElevationModeler.prepareTileRastersForRange(expandedTilesRange, this);

                log.info("[Tile][{}/{}][{}/{}] process quantized mesh tiling...", depth, maxTileDepth, progress, total);
                TileMatrix tileMatrix = new TileMatrix(subDividedTilesRange, this);

                boolean isFirstGeneration = (depth == minTileDepth);
                tileMatrix.makeMatrixMesh(isFirstGeneration);
                tileMatrix.deleteObjects();

                if (!context.isLeaveTemp()) {

                    TileRange tilesToDeleteRange = subDividedTilesRange.clone();

                    if (tilesToDeleteRange.getMaxTileX() - tilesToDeleteRange.getMinTileX() > 6 &&
                            tilesToDeleteRange.getMaxTileY() - tilesToDeleteRange.getMinTileY() > 6) {
                        tilesToDeleteRange = tilesToDeleteRange.expand(-1);
                        deleteTempFilesByTileRange(tilesToDeleteRange);
                    }
                }
            }

            this.terrainElevationModeler.deleteGeoTiffManager();
            this.terrainElevationModeler.deleteTileRaster();
            this.terrainElevationModeler.deleteCoverage();

            if (!context.isLeaveTemp()) {
                this.deleteTempFilesByDepth(depth);
            }

            long endTime = System.currentTimeMillis();
            log.info("[Tile][{}/{}] - End making tile meshes : Duration: {}", depth, maxTileDepth, DecimalUtils.millisecondToDisplayTime(endTime - startTime));

            String javaHeapSize = System.getProperty("java.vm.name") + " " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB";
            String maxMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().maxMemory());
            String totalMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().totalMemory());
            String freeMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().freeMemory());
            String usedMem = DecimalUtils.byteCountToDisplaySize(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            log.debug("[Tile][{}/{}] Java Heap Size: {} - MaxMem: {}MB / TotalMem: {}MB / FreeMem: {}MB / UsedMem: {}MB ({}%)", depth, maxTileDepth, javaHeapSize, maxMem, totalMem, freeMem, usedMem);
            log.info("----------------------------------------");
        }
        terrainLayer.saveJsonFileCustom(context.getOutputPath(), "layer.json", this.availableTileSet);
    }

    private void saveLayerJsonBeforeTileGeneration() {
        log.info("[Pre][layer.json] Save layer.json before terrain tile generation.");
        terrainLayer.saveJsonFileCustom(context.getOutputPath(), "layer.json", this.availableTileSet);
    }

    private void deleteTempFilesByDepth(int depth) {
        terrainTileStore.deleteTempFilesByDepth(depth);
    }

    private void deleteTempFilesByTileRange(TileRange tileRange) {
        terrainTileStore.deleteTempFilesByTileRange(tileRange);
    }

    public String timeFormat(long time) {
        long ms = time % 1000;
        long s = (time / 1000) % 60;
        long m = (time / (1000 * 60)) % 60;
        long h = (time / (1000 * 60 * 60)) % 24;
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms);
    }

    public double getMaxTriangleSizeForTileDepth(int depth) {
        return maxTriangleSizeForTileDepthList.get(depth);
    }

    public double getMinTriangleSizeForTileDepth(int depth) {
        return minTriangleSizeForTileDepthList.get(depth);
    }

    public double getMaxDiffBetweenGeoTiffSampleAndTrianglePlane(int depth) {
        if (depthMaxDiffBetweenGeoTiffSampleAndTrianglePlaneMap.containsKey(depth)) {
            return depthMaxDiffBetweenGeoTiffSampleAndTrianglePlaneMap.get(depth);
        } else {
            double maxDiff = GeographicTerrainTileUtils.getMaxDiffBetweenGeoTiffSampleAndTrianglePlane(depth);
            depthMaxDiffBetweenGeoTiffSampleAndTrianglePlaneMap.put(depth, maxDiff);
            return depthMaxDiffBetweenGeoTiffSampleAndTrianglePlaneMap.get(depth);
        }
    }

    public String getTilePath(TileIndices tileIndices) {
        return terrainTileStore.getTilePath(tileIndices);
    }

    public String getQuantizedMeshTileFolderPath(TileIndices tileIndices) {
        return terrainTileStore.getQuantizedMeshTileFolderPath(tileIndices);
    }

    public String getQuantizedMeshTilePath(TileIndices tileIndices) {
        return terrainTileStore.getQuantizedMeshTilePath(tileIndices);
    }

    public GeographicTerrainTile loadOrCreateGeographicTerrainTile(TileIndices tileIndices) throws IOException, TransformException {
        return terrainTileStore.loadOrCreateGeographicTerrainTile(tileIndices);
    }

    public GeographicTerrainTile loadGeographicTerrainTile(TileIndices tileIndices) throws IOException {
        return terrainTileStore.loadGeographicTerrainTile(tileIndices);
    }

    public List<File> resolveRasterFilesForDepth(int depth) {
        return rasterPreprocessor.resolveRasterFilesForDepth(depth);
    }

    public boolean originIsLeftUp() {
        return this.originIsLeftUp;
    }

}
