/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.mesh;

import com.gaia3d.basic.geometry.GaiaBoundingBox;
import com.gaia3d.terrain.tile.generation.TerrainTilingContext;
import com.gaia3d.io.LittleEndianDataOutputStream;
import com.gaia3d.quantized.mesh.QuantizedMesh;
import com.gaia3d.quantized.mesh.QuantizedMeshManager;
import com.gaia3d.terrain.structure.*;
import com.gaia3d.terrain.tile.core.*;
import com.gaia3d.terrain.tile.elevation.TerrainElevationModeler;
import com.gaia3d.terrain.tile.generation.TerrainTilesetGenerator;
import com.gaia3d.terrain.types.TerrainObjectStatus;
import com.gaia3d.terrain.util.GeographicTerrainTileUtils;
import com.gaia3d.terrain.util.MemoryMonitor;
import com.gaia3d.terrain.util.TerrainMeshUtils;
import com.gaia3d.util.CelestialBody;
import com.gaia3d.util.FileUtils;
import com.gaia3d.util.GeometryUtils;
import com.gaia3d.util.GlobeUtils;
import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.operation.TransformException;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.abs;

@Slf4j
public class TileMatrix {
    private final TileRange tilesRange;
    private final List<List<GeographicTerrainTile>> tilesMatrixRowCol = new ArrayList<>();
    private final Vector3d barycenterScratch = new Vector3d();
    private final RasterTriangle rasterTriangleScratch = new RasterTriangle();
    private final TerrainTilingContext context;
    public TerrainTilesetGenerator manager = null;

    List<TerrainVertex> listVertices = new ArrayList<>();
    List<TerrainHalfEdge> listHalfEdges = new ArrayList<>();
    List<TerrainHalfEdge> secondHalfEdges = new ArrayList<>();

    public TileMatrix(TileRange tilesRange, TerrainTilesetGenerator manager) {
        this.tilesRange = tilesRange;
        this.manager = manager;
        this.context = manager.getContext();
    }

    public void deleteObjects() {
        for (List<GeographicTerrainTile> row : tilesMatrixRowCol) {
            for (GeographicTerrainTile tile : row) {
                if (tile != null) {tile.deleteObjects();}
            }
        }

        listVertices.clear();
        listHalfEdges.clear();
    }

    private boolean refineAdjacentTilesVertically(TerrainMesh upMesh, TerrainMesh downMesh, boolean isModify) {
        if (upMesh == null || downMesh == null) {
            return false;
        }

        List<TerrainVertex> upTileDownVertices = upMesh.getDownVerticesSortedLeftToRight();
        List<TerrainVertex> downTileUpVertices = downMesh.getUpVerticesSortedRightToLeft();

        if (upTileDownVertices.size() != downTileUpVertices.size()) {

            int splitCountUp = 0;
            int splitCountDown = 0;

            splitCountUp = refineTileOneSide(upMesh, downTileUpVertices, CardinalDirection.SOUTH, isModify);
            splitCountDown = refineTileOneSide(downMesh, upTileDownVertices, CardinalDirection.NORTH, isModify);

            return true;
        }

        return false;
    }

    private boolean refineAdjacentTilesHorizontally(TerrainMesh leftMesh, TerrainMesh rightMesh, boolean isModify) {
        if (leftMesh == null || rightMesh == null) {
            return false;
        }

        List<TerrainVertex> leftTileRightVertices = leftMesh.getRightVerticesSortedDownToUp();
        List<TerrainVertex> rightTileLeftVertices = rightMesh.getLeftVerticesSortedUpToDown();

        if (leftTileRightVertices.size() != rightTileLeftVertices.size()) {

            int splitCountLeft = 0;
            int splitCountRight = 0;
            splitCountLeft = refineTileOneSide(leftMesh, rightTileLeftVertices, CardinalDirection.EAST, isModify);
            splitCountRight = refineTileOneSide(rightMesh, leftTileRightVertices, CardinalDirection.WEST, isModify);
            return true;
        }

        return false;
    }

    private int refineTileOneSide(TerrainMesh meshToRefine, List<TerrainVertex> vertices, CardinalDirection tileSide, boolean isModify) {
        double error = 1e-6;
        int verticesCount = vertices.size();
        boolean triangleSplit = false;
        boolean finished = false;
        int counter = 0;
        int splitCount = 0;
        List<TerrainHalfEdge> halfEdgesToRefine = null;
        while (!finished) {
            context.checkCancelled();
            triangleSplit = false;
            for (int i = 0; i < verticesCount; i++) {
                TerrainVertex vertex = vertices.get(i);
                if (vertex.getObjectStatus() == TerrainObjectStatus.DELETED) {
                    continue;
                }
                if (tileSide == CardinalDirection.SOUTH) {
                    halfEdgesToRefine = meshToRefine.getDownHalfEdgesSortedLeftToRight();
                } else if (tileSide == CardinalDirection.NORTH) {
                    halfEdgesToRefine = meshToRefine.getUpHalfEdgesSortedRightToLeft();
                } else if (tileSide == CardinalDirection.EAST) {
                    halfEdgesToRefine = meshToRefine.getRightHalfEdgesSortedDownToUp();
                } else if (tileSide == CardinalDirection.WEST) {
                    halfEdgesToRefine = meshToRefine.getLeftHalfEdgesSortedUpToDown();
                }

                int hedgesCount = halfEdgesToRefine.size();
                for (int j = 0; j < hedgesCount; j++) {
                    TerrainHalfEdge hEdge = halfEdgesToRefine.get(j);
                    if (hEdge.getObjectStatus() == TerrainObjectStatus.DELETED) {
                        continue;
                    }

                    if (hEdge.intersectsPoint(vertex.getPosition(), error) == 1) {

                        TerrainTriangle triangle = hEdge.getTriangle();
                        try {
                            this.listHalfEdges.clear();
                            meshToRefine.splitTriangle(triangle, this.manager.getTerrainElevationModeler(), this.manager.getTriangleList(), this.listHalfEdges, isModify, null);
                            this.listHalfEdges.clear();
                            splitCount++;
                        } catch (Exception e) {
                            log.warn("Refine adjacent tiles horizontally, counter : {}", counter);
                        }

                        meshToRefine.removeDeletedObjects();
                        meshToRefine.setObjectsIdInList();

                        triangleSplit = true;
                        break;
                    }
                }
            }

            meshToRefine.removeDeletedObjects();
            meshToRefine.setObjectsIdInList();

            if (!triangleSplit) {
                finished = true;
                break;
            }

            counter++;
            if (counter > 100) {
                log.warn("Refine adjacent tiles horizontally, counter : {}", counter);
                break;
            }
        }

        return splitCount;
    }

    private boolean setTwinsBetweenHalfEdgesInverseOrder(List<TerrainHalfEdge> listHEdges_A, List<TerrainHalfEdge> listHEdges_B) {
        if (listHEdges_A.size() != listHEdges_B.size()) {
            log.error("The size of the halfEdges lists are different." + "A_size: " + listHEdges_A.size() + ", B_size: " + listHEdges_B.size());
            return false;
        }

        int countA = listHEdges_A.size();
        int countB = listHEdges_B.size();
        int minCount = Math.min(countA, countB);

        for (int i = 0; i < minCount; i++) {
            context.checkCancelled();
            TerrainHalfEdge halfEdge = listHEdges_A.get(i);
            if (halfEdge.getTwin() != null) {
                continue;
            }

            int idxInverse = minCount - i - 1;
            TerrainHalfEdge halfEdge2 = listHEdges_B.get(idxInverse);
            if (halfEdge2.getTwin() != null) {
                continue;
            }

            TerrainVertex startVertex = halfEdge.getStartVertex();
            TerrainVertex endVertex = halfEdge.getEndVertex();

            TerrainVertex startVertex2 = halfEdge2.getStartVertex();
            TerrainVertex endVertex2 = halfEdge2.getEndVertex();

            List<TerrainHalfEdge> outingHalfEdges_strVertex2 = startVertex2.getAllOutingHalfEdges(listHalfEdges);
            List<TerrainHalfEdge> outingHalfEdges_endVertex2 = endVertex2.getAllOutingHalfEdges(secondHalfEdges);

            for (TerrainHalfEdge outingHalfEdge : outingHalfEdges_strVertex2) {

                outingHalfEdge.setStartVertex(endVertex);
            }

            for (TerrainHalfEdge outingHalfEdge : outingHalfEdges_endVertex2) {

                outingHalfEdge.setStartVertex(startVertex);
            }

            halfEdge.setTwin(halfEdge2);

            if (!startVertex2.equals(endVertex)) {

                if (startVertex2.getOutingHEdge() != null) {
                    startVertex2.setOutingHEdge(null);
                }
                startVertex2.setObjectStatus(TerrainObjectStatus.DELETED);
            }

            if (!endVertex2.equals(startVertex)) {
                if (endVertex2.getOutingHEdge() != null) {
                    endVertex2.setOutingHEdge(null);
                }
                endVertex2.setObjectStatus(TerrainObjectStatus.DELETED);
            }

            if (startVertex.getOutingHEdge() == null || startVertex.getOutingHEdge().getObjectStatus() == TerrainObjectStatus.DELETED) {

                for (TerrainHalfEdge outingHalfEdge : outingHalfEdges_endVertex2) {
                    if (outingHalfEdge.getObjectStatus() != TerrainObjectStatus.DELETED) {
                        startVertex.setOutingHEdge(outingHalfEdge);
                        break;
                    }
                }
            }

            if (endVertex.getOutingHEdge() == null || endVertex.getOutingHEdge().getObjectStatus() == TerrainObjectStatus.DELETED) {
                for (TerrainHalfEdge outingHalfEdge : outingHalfEdges_strVertex2) {
                    if (outingHalfEdge.getObjectStatus() != TerrainObjectStatus.DELETED) {
                        endVertex.setOutingHEdge(outingHalfEdge);
                        break;
                    }
                }
            }

            if (startVertex.getOutingHEdge() == null || startVertex.getOutingHEdge().getObjectStatus() == TerrainObjectStatus.DELETED) {

                boolean foundValid = false;
                for (TerrainHalfEdge edge : listHEdges_A) {
                    if (edge.getStartVertex() == startVertex && edge.getObjectStatus() != TerrainObjectStatus.DELETED) {
                        startVertex.setOutingHEdge(edge);
                        foundValid = true;
                        break;
                    }
                }

                if (!foundValid) {
                    log.warn("Failed to find valid outingHEdge for vertex {} after consolidation. " + "Marking as DELETED to prevent topology corruption.", startVertex.getId());
                    startVertex.setOutingHEdge(null);
                    startVertex.setObjectStatus(TerrainObjectStatus.DELETED);
                }
            }

            if (endVertex.getOutingHEdge() == null || endVertex.getOutingHEdge().getObjectStatus() == TerrainObjectStatus.DELETED) {

                boolean foundValid = false;
                for (TerrainHalfEdge edge : listHEdges_A) {
                    if (edge.getStartVertex() == endVertex && edge.getObjectStatus() != TerrainObjectStatus.DELETED) {
                        endVertex.setOutingHEdge(edge);
                        foundValid = true;
                        break;
                    }
                }

                if (!foundValid) {
                    log.warn("Failed to find valid outingHEdge for vertex {} after consolidation. " + "Marking as DELETED to prevent topology corruption.", endVertex.getId());
                    endVertex.setOutingHEdge(null);
                    endVertex.setObjectStatus(TerrainObjectStatus.DELETED);
                }
            }
        }

        return true;
    }

    public void makeMatrixMeshModifyMode(boolean isFirstGeneration) throws TransformException, IOException {
        TileIndices tileIndices = new TileIndices();

        boolean originIsLeftUp = this.manager.isOriginIsLeftUp();

        tilesMatrixRowCol.clear();
        int minTileX = tilesRange.getMinTileX() - 1;
        int maxTileX = tilesRange.getMaxTileX() + 1;
        int minTileY = tilesRange.getMinTileY() - 1;
        int maxTileY = tilesRange.getMaxTileY() + 1;

        int totalTiles = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);

        int counter = 0;
        int counterAux = 0;
        for (int Y = minTileY; Y <= maxTileY; Y++) {
            context.checkCancelled();
            List<GeographicTerrainTile> tilesListRow = new ArrayList<>();
            for (int X = minTileX; X <= maxTileX; X++) {
                tileIndices.set(X, Y, tilesRange.getTileDepth());

                GeographicTerrainTile tile = this.manager.loadOrCreateGeographicTerrainTile(tileIndices);
                if (counter >= 100) {
                    counter = 0;
                    log.debug("Loading Tile Level : {}, i : {}/{}", tileIndices.getL(), counterAux, totalTiles);
                }

                tilesListRow.add(tile);

                counter++;
                counterAux++;
            }
            tilesMatrixRowCol.add(tilesListRow);
        }

        int rowsCount = tilesMatrixRowCol.size();
        int colsCount = tilesMatrixRowCol.get(0).size();
        log.debug("Making TileMatrix columns : {}, rows : {} ", colsCount, rowsCount);

        List<TerrainMesh> rowMeshesList = new ArrayList<>();
        for (int i = 0; i < rowsCount; i++) {
            context.checkCancelled();
            List<GeographicTerrainTile> rowTilesArray = tilesMatrixRowCol.get(i);
            TerrainMesh rowMesh = null;

            for (int j = 0; j < colsCount; j++) {
                GeographicTerrainTile tile = rowTilesArray.get(j);
                if (tile != null) {
                    TerrainMesh tileMesh = tile.getMesh();
                    if (rowMesh == null) {
                        rowMesh = tileMesh;
                    } else {

                        List<TerrainHalfEdge> rowMeshRightHalfEdges = rowMesh.getRightHalfEdgesSortedDownToUp();
                        List<TerrainHalfEdge> tileMeshLeftHalfEdges = tileMesh.getLeftHalfEdgesSortedUpToDown();

                        if (!rowMeshRightHalfEdges.isEmpty()) {
                            if (rowMeshRightHalfEdges.size() != tileMeshLeftHalfEdges.size()) {
                                log.debug("The size of the halfEdges lists are different." + "rowMeshRightHalfEdges_size: " + rowMeshRightHalfEdges.size() + ", tileMeshLeftHalfEdges_size: " + tileMeshLeftHalfEdges.size());
                                refineAdjacentTilesHorizontally(rowMesh, tileMesh, context.isModify());
                            }
                            this.setTwinsBetweenHalfEdgesInverseOrder(rowMeshRightHalfEdges, tileMeshLeftHalfEdges);

                            rowMesh.removeDeletedObjects();
                            rowMesh.mergeMesh(tileMesh);
                        }
                    }
                }
            }
            rowMeshesList.add(rowMesh);
        }

        TerrainMesh resultMesh = null;
        for (TerrainMesh rowMesh : rowMeshesList) {
            context.checkCancelled();
            if (rowMesh == null) {
                continue;
            }

            if (resultMesh == null) {
                resultMesh = rowMesh;
            } else {
                if (originIsLeftUp) {

                    List<TerrainHalfEdge> resultMeshDownHalfEdges = resultMesh.getDownHalfEdgesSortedLeftToRight();
                    List<TerrainHalfEdge> rowMeshUpHalfEdges = rowMesh.getUpHalfEdgesSortedRightToLeft();

                    if (!resultMeshDownHalfEdges.isEmpty()) {

                        if (resultMeshDownHalfEdges.size() != rowMeshUpHalfEdges.size()) {
                            log.debug("The size of the halfEdges lists are different." + "resultMeshDownHalfEdges_size: " + resultMeshDownHalfEdges.size() + ", rowMeshUpHalfEdges_size: " + rowMeshUpHalfEdges.size());
                            refineAdjacentTilesVertically(resultMesh, rowMesh, context.isModify());
                        }
                        this.setTwinsBetweenHalfEdgesInverseOrder(resultMeshDownHalfEdges, rowMeshUpHalfEdges);

                        resultMesh.removeDeletedObjects();
                        resultMesh.mergeMesh(rowMesh);
                    }
                } else {

                    List<TerrainHalfEdge> resultMeshUpHalfEdges = resultMesh.getUpHalfEdgesSortedRightToLeft();
                    List<TerrainHalfEdge> rowMeshDownHalfEdges = rowMesh.getDownHalfEdgesSortedLeftToRight();

                    if (!resultMeshUpHalfEdges.isEmpty()) {

                        this.setTwinsBetweenHalfEdgesInverseOrder(resultMeshUpHalfEdges, rowMeshDownHalfEdges);

                        resultMesh.removeDeletedObjects();
                        resultMesh.mergeMesh(rowMesh);
                    }
                }
            }
        }

        log.debug("End making TileMatrix");

        if (resultMesh != null) {
            resultMesh.setObjectsIdInList();

            boolean skipNoDataType = true;

            this.recalculateElevation(resultMesh, tilesRange, skipNoDataType);
            this.refineMesh(resultMesh, tilesRange);

            if (context.isCalculateNormalsExtension()) {
                this.listVertices.clear();
                this.listHalfEdges.clear();
                resultMesh.calculateNormals(this.listVertices, this.listHalfEdges);
            }

            List<TerrainMesh> separatedMeshes = new ArrayList<>();
            TerrainMeshUtils.getSeparatedMeshes(resultMesh, separatedMeshes, originIsLeftUp);

            log.debug("Saving Separated Tiles...");
            saveSeparatedTiles(separatedMeshes);

            log.debug("Saving Quantized Meshes...");
            saveQuantizedMeshes(separatedMeshes);

            if (tilesRange.getTileDepth() < context.getMaximumTileDepth()) {
                log.debug("Saving Separated children Tiles...");
                saveSeparatedChildrenTiles(separatedMeshes);
            }
        } else {
            log.error("ResultMesh is null.");
        }
    }

    public void makeMatrixMesh(boolean isFirstGeneration) throws TransformException, IOException {
        TileIndices tileIndices = new TileIndices();

        boolean originIsLeftUp = this.manager.isOriginIsLeftUp();

        tilesMatrixRowCol.clear();
        int minTileX = tilesRange.getMinTileX() - 1;
        int maxTileX = tilesRange.getMaxTileX() + 1;
        int minTileY = tilesRange.getMinTileY() - 1;
        int maxTileY = tilesRange.getMaxTileY() + 1;

        int totalTiles = (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);

        int counter = 0;
        int counterAux = 0;
        for (int Y = minTileY; Y <= maxTileY; Y++) {
            context.checkCancelled();
            List<GeographicTerrainTile> tilesListRow = new ArrayList<>();
            for (int X = minTileX; X <= maxTileX; X++) {
                tileIndices.set(X, Y, tilesRange.getTileDepth());
                GeographicTerrainTile tile = null;
                if (isFirstGeneration) {
                    tile = this.manager.loadOrCreateGeographicTerrainTile(tileIndices);
                } else {
                    tile = this.manager.loadGeographicTerrainTile(tileIndices);
                }
                if (counter >= 100) {
                    counter = 0;
                    log.debug("Loading Tile Level : {}, i : {}/{}", tileIndices.getL(), counterAux, totalTiles);
                }

                tilesListRow.add(tile);

                counter++;
                counterAux++;
            }
            tilesMatrixRowCol.add(tilesListRow);
        }

        int rowsCount = tilesMatrixRowCol.size();
        int colsCount = tilesMatrixRowCol.get(0).size();
        log.debug("Making TileMatrix columns : {}, rows : {} ", colsCount, rowsCount);

        List<TerrainMesh> rowMeshesList = new ArrayList<>();
        for (int i = 0; i < rowsCount; i++) {
            context.checkCancelled();
            List<GeographicTerrainTile> rowTilesArray = tilesMatrixRowCol.get(i);
            TerrainMesh rowMesh = null;

            for (int j = 0; j < colsCount; j++) {
                GeographicTerrainTile tile = rowTilesArray.get(j);
                if (tile != null) {
                    TerrainMesh tileMesh = tile.getMesh();
                    if (rowMesh == null) {
                        rowMesh = tileMesh;
                    } else {

                        List<TerrainHalfEdge> rowMeshRightHalfEdges = rowMesh.getRightHalfEdgesSortedDownToUp();
                        List<TerrainHalfEdge> tileMeshLeftHalfEdges = tileMesh.getLeftHalfEdgesSortedUpToDown();

                        if (!rowMeshRightHalfEdges.isEmpty()) {
                            this.setTwinsBetweenHalfEdgesInverseOrder(rowMeshRightHalfEdges, tileMeshLeftHalfEdges);

                            rowMesh.removeDeletedObjects();
                            rowMesh.mergeMesh(tileMesh);
                        }
                    }
                }
            }
            rowMeshesList.add(rowMesh);
        }

        TerrainMesh resultMesh = null;
        for (TerrainMesh rowMesh : rowMeshesList) {
            context.checkCancelled();
            if (rowMesh == null) {
                continue;
            }

            if (resultMesh == null) {
                resultMesh = rowMesh;
            } else {
                if (originIsLeftUp) {

                    List<TerrainHalfEdge> resultMeshDownHalfEdges = resultMesh.getDownHalfEdgesSortedLeftToRight();
                    List<TerrainHalfEdge> rowMeshUpHalfEdges = rowMesh.getUpHalfEdgesSortedRightToLeft();

                    if (!resultMeshDownHalfEdges.isEmpty()) {

                        this.setTwinsBetweenHalfEdgesInverseOrder(resultMeshDownHalfEdges, rowMeshUpHalfEdges);

                        resultMesh.removeDeletedObjects();
                        resultMesh.mergeMesh(rowMesh);
                    }
                } else {

                    List<TerrainHalfEdge> resultMeshUpHalfEdges = resultMesh.getUpHalfEdgesSortedRightToLeft();
                    List<TerrainHalfEdge> rowMeshDownHalfEdges = rowMesh.getDownHalfEdgesSortedLeftToRight();

                    if (!resultMeshUpHalfEdges.isEmpty()) {

                        this.setTwinsBetweenHalfEdgesInverseOrder(resultMeshUpHalfEdges, rowMeshDownHalfEdges);

                        resultMesh.removeDeletedObjects();
                        resultMesh.mergeMesh(rowMesh);

                    }
                }
            }
        }

        log.debug("End making TileMatrix");

        if (resultMesh != null) {
            resultMesh.setObjectsIdInList();

            boolean skipNoDataType = false;
            this.recalculateElevation(resultMesh, tilesRange, skipNoDataType);
            this.refineMesh(resultMesh, tilesRange);

            if (context.isCalculateNormalsExtension()) {
                this.listVertices.clear();
                this.listHalfEdges.clear();
                resultMesh.calculateNormals(this.listVertices, this.listHalfEdges);
            }

            List<TerrainMesh> separatedMeshes = new ArrayList<>();
            TerrainMeshUtils.getSeparatedMeshes(resultMesh, separatedMeshes, originIsLeftUp);

            log.debug("Saving Separated Tiles...");
            saveSeparatedTiles(separatedMeshes);

            log.debug("Saving Quantized Meshes...");
            saveQuantizedMeshes(separatedMeshes);

            if (tilesRange.getTileDepth() < context.getMaximumTileDepth()) {
                log.debug("Saving Separated children Tiles...");
                saveSeparatedChildrenTiles(separatedMeshes);
            }
        } else {
            log.error("ResultMesh is null.");
        }
    }

    public void saveQuantizedMeshes(List<TerrainMesh> separatedMeshes) throws IOException {
        boolean originIsLeftUp = this.manager.isOriginIsLeftUp();
        boolean calculateNormals = context.isCalculateNormalsExtension();

        for (TerrainMesh mesh : separatedMeshes) {
            context.checkCancelled();
            TerrainTriangle triangle = mesh.triangles.get(0);
            TileIndices tileIndices = triangle.getOwnerTileIndices();

            GeographicTerrainTile tile = new GeographicTerrainTile(null, this.manager);
            tile.setTileIndices(tileIndices);
            String imageryType = this.manager.getImaginaryType();
            tile.setGeographicExtension(GeographicTerrainTileUtils.getGeographicExtentOfTileLXY(tileIndices.getL(), tileIndices.getX(), tileIndices.getY(), null, imageryType, originIsLeftUp));
            tile.setMesh(mesh);

            QuantizedMeshManager quantizedMeshManager = new QuantizedMeshManager();
            QuantizedMesh quantizedMesh = quantizedMeshManager.getQuantizedMeshFromTile(tile, calculateNormals);
            String tileFullPath = this.manager.getQuantizedMeshTilePath(tileIndices);
            String tileFolderPath = this.manager.getQuantizedMeshTileFolderPath(tileIndices);
            FileUtils.createAllFoldersIfNoExist(tileFolderPath);

            try (LittleEndianDataOutputStream dataOutputStream = new LittleEndianDataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tileFullPath)))) {
                quantizedMesh.saveDataOutputStream(dataOutputStream, calculateNormals);
            }
            context.tileWritten();
        }
    }

    public boolean saveSeparatedTiles(List<TerrainMesh> separatedMeshes) {
        int meshesCount = separatedMeshes.size();
        int counter = 0;
        for (int i = 0; i < meshesCount; i++) {
            context.checkCancelled();
            TerrainMesh mesh = separatedMeshes.get(i);

            TerrainTriangle triangle = mesh.triangles.getFirst();
            TileIndices tileIndices = triangle.getOwnerTileIndices();
            String tileTempDirectory = context.getTileTempPath();
            String tileFilePath = GeographicTerrainTileUtils.getTileFilePath(tileIndices.getX(), tileIndices.getY(), tileIndices.getL());
            String tileFullPath = tileTempDirectory + File.separator + tileFilePath;

            if (counter >= 100) {
                counter = 0;
                log.debug("Saving separated tiles... L : " + tileIndices.getL() + " i : " + i + " / " + meshesCount);
            }

            try {
                mesh.saveFile(tileFullPath);
            } catch (IOException e) {
                log.error("Error:", e);
                return false;
            }
            counter++;
        }

        return true;
    }

    private void saveSeparatedChildrenTiles(List<TerrainMesh> separatedMeshes) {
        for (TerrainMesh mesh : separatedMeshes) {
            context.checkCancelled();
            TerrainMeshUtils.save4ChildrenMeshes(mesh, this.manager, context);
        }
    }

    public void recalculateElevation(TerrainMesh terrainMesh, TileRange tilesRange, boolean skipNoDataType) throws TransformException, IOException {
        List<TerrainTriangle> triangles = new ArrayList<>();
        terrainMesh.getTrianglesByTilesRange(tilesRange, triangles, null);

        HashMap<TerrainVertex, TerrainVertex> mapVertices = new HashMap<>();
        for (TerrainTriangle triangle : triangles) {
            context.checkCancelled();
            this.listVertices.clear();
            this.listHalfEdges.clear();
            this.listVertices = triangle.getVertices(this.listVertices, this.listHalfEdges);
            for (TerrainVertex vertex : this.listVertices) {
                mapVertices.put(vertex, vertex);
            }
        }

        List<TerrainVertex> verticesOfCurrentTile = new ArrayList<>(mapVertices.values());
        TerrainElevationModeler terrainElevationModeler = this.manager.getTerrainElevationModeler();

        int verticesCount = verticesOfCurrentTile.size();
        log.debug("recalculating elevations... vertices count : " + verticesCount);
        TileIndices tileIndicesAux = new TileIndices();
        boolean originIsLeftUp = this.manager.isOriginIsLeftUp();
        int currDepth = tilesRange.getTileDepth();
        byte[] intersectionType = {0};

        for (int i = 0; i < verticesCount; i++) {
            context.checkCancelled();
            TerrainVertex vertex = verticesOfCurrentTile.get(i);
            Vector3d position = vertex.getPosition();

            GeographicTerrainTileUtils.selectTileIndices(currDepth, position.x, position.y, tileIndicesAux, originIsLeftUp);
            double z = terrainElevationModeler.sampleBilinearElevation(tileIndicesAux, this.manager, position.x, position.y, intersectionType);
            if (skipNoDataType) {

                if (intersectionType[0] == 1) {

                    position.z = z;
                }
            } else {
                position.z = z;
            }
        }
    }

    public boolean mustRefineTriangle(TerrainTriangle triangle) {
        if (triangle.isRefineChecked()) {
            return false;
        }

        TerrainElevationModeler terrainElevationModeler = this.manager.getTerrainElevationModeler();
        TileIndices tileIndices = triangle.getOwnerTileIndices();
        this.listVertices.clear();
        this.listHalfEdges.clear();
        this.listVertices = triangle.getVertices(this.listVertices, this.listHalfEdges);

        GaiaBoundingBox bboxTriangle = triangle.getBoundingBox(this.listVertices, this.listHalfEdges);
        double bboxMaxLength = bboxTriangle.getLongestDistanceXY();
        double equatorialRadius = context.getCelestialBody().getEquatorialRadius();
        double bboxMaxLengthInMeters = Math.toRadians(bboxMaxLength) * equatorialRadius;

        int currL = tileIndices.getL();

        double tileSize = GeographicTerrainTileUtils.getTileSizeInMetersByDepth(currL);
        double scale = bboxMaxLengthInMeters / tileSize;

        scale = 0.8 * scale + 0.2;

        double maxDiff = this.manager.getMaxDiffBetweenGeoTiffSampleAndTrianglePlane(triangle.getOwnerTileIndices().getL());
        maxDiff *= scale;

        GeographicTerrainTileRaster tileRaster = terrainElevationModeler.getGeographicTerrainTileRaster(tileIndices, this.manager);

        double triangleMaxLengthMeters = Math.toRadians(Math.max(bboxTriangle.getLengthX(), bboxTriangle.getLengthY())) * equatorialRadius;
        double minTriangleSizeForDepth = this.manager.getMinTriangleSizeForTileDepth(currL);

        if (triangleMaxLengthMeters < minTriangleSizeForDepth) {
            triangle.setRefineChecked(true);
            log.debug("Filtered by Min Triangle Size : L : " + tileIndices.getL() + " # triangleMaxLengthMeters : " + triangleMaxLengthMeters + " # minTriangleSizeForDepth : " + minTriangleSizeForDepth);
            return false;
        }

        double maxTriangleSizeForDepth = this.manager.getMaxTriangleSizeForTileDepth(currL);
        if (triangleMaxLengthMeters > maxTriangleSizeForDepth) {
            log.debug("Filtered by Max Triangle Size : L : " + tileIndices.getL() + " # triangleMaxLengthMeters : " + triangleMaxLengthMeters + " # maxTriangleSizeForDepth : " + maxTriangleSizeForDepth);
            return true;
        }

        GeographicExtension rootGeographicExtension = terrainElevationModeler.getRootGeographicExtension();
        if (!rootGeographicExtension.intersectsBox(bboxTriangle.getMinX(), bboxTriangle.getMinY(), bboxTriangle.getMaxX(), bboxTriangle.getMaxY())) {

            for (TerrainVertex vertex : this.listVertices) {
                if (vertex.getPosition().z > maxDiff) {
                    return true;
                }
            }

            return false;
        }

        if (tileRaster == null) {
            return false;
        }

        float cosAng = 1.0f;
        boolean requiresCosAng = tileIndices.getL() > 10;
        boolean cosAngComputed = !requiresCosAng;

        this.listVertices.clear();
        this.listHalfEdges.clear();
        TerrainPlane plane = triangle.getPlane(this.listVertices, this.listHalfEdges);
        triangle.getBarycenter(this.listVertices, this.listHalfEdges, this.barycenterScratch);
        int colIdx = tileRaster.getColumn(this.barycenterScratch.x);
        int rowIdx = tileRaster.getRow(this.barycenterScratch.y);
        double barycenterLonDeg = tileRaster.getLonDeg(colIdx);
        double barycenterLatDeg = tileRaster.getLatDeg(rowIdx);

        double elevation = tileRaster.getElevation(colIdx, rowIdx);
        double planeElevation = plane.getValueZ(barycenterLonDeg, barycenterLatDeg);
        double rawDistToPlane = abs(elevation - planeElevation);
        double distToPlane = rawDistToPlane;

        if (rawDistToPlane > maxDiff) {
            if (!cosAngComputed) {
                cosAng = computeCosAng(triangle, tileRaster);
                cosAngComputed = true;
            }
            distToPlane = rawDistToPlane * cosAng;
        }

        if (distToPlane > maxDiff) {

            log.debug("Filtered by Barycenter : L : " + tileIndices.getL() + " # col : " + colIdx + " # row : " + rowIdx + " # distToPlane : " + distToPlane + " # maxDiff : " + maxDiff);
            return true;
        }

        int startCol = tileRaster.getColumn(bboxTriangle.getMinX());
        int startRow = tileRaster.getRow(bboxTriangle.getMinY());
        int endCol = tileRaster.getColumn(bboxTriangle.getMaxX());
        int endRow = tileRaster.getRow(bboxTriangle.getMaxY());

        int colsCount = endCol - startCol + 1;
        int rowsCount = endRow - startRow + 1;

        if (colsCount < 6 || rowsCount < 6) {
            triangle.setRefineChecked(true);
            return false;
        }

        tileRaster.populateRasterTriangle(triangle, this.listVertices, this.listHalfEdges, this.rasterTriangleScratch);
        RasterTriangle rasterTriangle = this.rasterTriangleScratch;
        if (rasterTriangle == null || rasterTriangle.p1() == null || rasterTriangle.p2() == null || rasterTriangle.p3() == null) {
            log.warn("Unable to get valid raster triangle for triangle {}. Skipping refinement check.", triangle.getId());
            triangle.setRefineChecked(true);
            return false;
        }
        Vector2i rasterTriangleP1 = rasterTriangle.p1();
        Vector2i rasterTriangleP2 = rasterTriangle.p2();
        Vector2i rasterTriangleP3 = rasterTriangle.p3();

        int deltaYBC = rasterTriangleP2.y - rasterTriangleP3.y;
        int deltaYCA = rasterTriangleP3.y - rasterTriangleP1.y;
        int deltaYAC = rasterTriangleP1.y - rasterTriangleP2.y;
        int deltaXCB = rasterTriangleP3.x - rasterTriangleP2.x;
        int deltaXAC = rasterTriangleP1.x - rasterTriangleP3.x;

        double denominator = deltaYBC * deltaXAC + deltaXCB * deltaYAC;
        if (denominator == 0.0) {
            triangle.setRefineChecked(true);
            return false;
        }
        double inverseDenominator = 1.0 / denominator;

        double startLonDeg = tileRaster.getLonDeg(startCol);
        double startLatDeg = tileRaster.getLatDeg(startRow);

        double deltaLonDeg = tileRaster.getDeltaLonDeg();
        double deltaLatDeg = tileRaster.getDeltaLatDeg();

        double posX;
        double posY;

        int colAux = 0;
        int rowAux = 0;

        boolean intersects = false;
        for (int col = startCol; col <= endCol; col++) {
            context.checkCancelled();
            rowAux = 0;
            posX = startLonDeg + colAux * deltaLonDeg;
            int colOffsetFromP3 = col - rasterTriangleP3.x;
            for (int row = startRow; row <= endRow; row++) {

                if (col == startCol && row == startRow) {
                    rowAux++;
                    continue;
                } else if (col == startCol && row == endRow) {
                    rowAux++;
                    continue;
                } else if (col == endCol && row == startRow) {
                    rowAux++;
                    continue;
                } else if (col == endCol && row == endRow) {
                    rowAux++;
                    continue;
                }

                intersects = false;
                int rowOffsetFromP3 = row - rasterTriangleP3.y;
                double alpha = (deltaYBC * colOffsetFromP3 + deltaXCB * rowOffsetFromP3) * inverseDenominator;
                if (alpha < 0 || alpha > 1) {
                    rowAux++;
                    continue;
                }
                double beta = (deltaYCA * colOffsetFromP3 + deltaXAC * rowOffsetFromP3) * inverseDenominator;
                if (beta < 0 || beta > 1) {
                    rowAux++;
                    continue;
                }
                double gamma = 1.0 - alpha - beta;
                if (gamma < 0 || gamma > 1) {
                    rowAux++;
                    continue;
                }

                if (alpha >= 0 && beta >= 0 && gamma >= 0) {
                    intersects = true;
                }

                if (!intersects) {
                    rowAux++;
                    continue;
                }

                posY = startLatDeg + rowAux * deltaLatDeg;

                float elevationFloat = tileRaster.getElevation(col, row);

                planeElevation = plane.getValueZ(posX, posY);
                double rawSampleDistToPlane = abs(elevationFloat - planeElevation);
                distToPlane = rawSampleDistToPlane;
                if (rawSampleDistToPlane > maxDiff) {
                    if (!cosAngComputed) {
                        cosAng = computeCosAng(triangle, tileRaster);
                        cosAngComputed = true;
                    }
                    distToPlane = rawSampleDistToPlane * cosAng;
                }
                if (distToPlane > maxDiff) {

                    log.debug("Filtered by RasterTile : L : " + tileIndices.getL() + " # col : " + col + " / " + colsCount + " # row : " + row + " / " + rowsCount + " # cosAng : " + cosAng + " # distToPlane : " + distToPlane + " # maxDiff : " + maxDiff);
                    return true;
                }
                rowAux++;
            }
            colAux++;
        }
        triangle.setRefineChecked(true);

        log.debug("Filtered by RasterTile : L : " + tileIndices.getL() + " # col : " + colAux + " / " + colsCount + " # row : " + rowAux + " / " + rowsCount + " # cosAng : " + cosAng + " # distToPlane : " + distToPlane + " # maxDiff : " + maxDiff);
        return false;
    }

    private float computeCosAng(TerrainTriangle triangle, GeographicTerrainTileRaster tileRaster) {
        Vector3f triangleNormalWC = triangle.getNormal();
        Vector3d triangleNormalDouble = new Vector3d(triangleNormalWC.x, triangleNormalWC.y, triangleNormalWC.z);
        GeographicExtension geographicExtension = tileRaster.getGeographicExtension();
        Vector3d centerGeoCoord = geographicExtension.getMidPoint();
        CelestialBody body = context.getCelestialBody();
        double[] centerCartesian = GlobeUtils.geographicToCartesian(centerGeoCoord.x, centerGeoCoord.y, centerGeoCoord.z, body);
        Vector3d normalAtCartesian = GlobeUtils.normalAtCartesianPoint(centerCartesian[0], centerCartesian[1], centerCartesian[2], body);
        return (float) GeometryUtils.cosineBetweenUnitaryVectors(triangleNormalDouble.x, triangleNormalDouble.y, triangleNormalDouble.z, normalAtCartesian.x, normalAtCartesian.y, normalAtCartesian.z);
    }

    private boolean refineMeshOneIteration(TerrainMesh mesh, TileRange tilesRange) throws TransformException, IOException {

        boolean isModify = context.isModify();
        final int memoryCheckSplitInterval = 256;

        AtomicBoolean refined = new AtomicBoolean(false);
        AtomicInteger splitCount = new AtomicInteger();
        int trianglesCount = mesh.triangles.size();
        log.debug("[RefineMesh] Triangles count : {}", trianglesCount);
        for (int i = 0; i < trianglesCount; i++) {
            context.checkCancelled();
            TerrainTriangle triangle = mesh.triangles.get(i);

            if (triangle.getObjectStatus() == TerrainObjectStatus.DELETED) {
                continue;
            }

            if (!tilesRange.intersects(triangle.getOwnerTileIndices())) {
                continue;
            }
            if (mustRefineTriangle(triangle)) {
                int currentSplitCount = splitCount.get();
                if (currentSplitCount == 0 || currentSplitCount % memoryCheckSplitInterval == 0) {
                    MemoryMonitor.MemoryState memState = MemoryMonitor.checkMemory("RefineMesh-Split");
                    if (memState.isCritical) {
                        log.error("[RefineMesh] Stopping current iteration before split due to critical memory pressure: " + "free={}%, triangles={}, vertices={}, splits_this_iteration={}.", memState.getFormattedPercent(), mesh.triangles.size(), mesh.vertices.size(), currentSplitCount);
                        break;
                    }
                }

                this.manager.getTriangleList().clear();
                this.listHalfEdges.clear();
                mesh.splitTriangle(triangle, this.manager.getTerrainElevationModeler(), this.manager.getTriangleList(), this.listHalfEdges, isModify, null);
                this.listHalfEdges.clear();

                if (!this.manager.getTriangleList().isEmpty()) {
                    splitCount.getAndIncrement();
                    refined.set(true);
                }
                this.manager.getTriangleList().clear();
            }
        }

        if (refined.get()) {
            log.debug("Removing deleted Meshes : Splited count : {}", splitCount);
            mesh.removeDeletedObjects();
            mesh.setObjectsIdInList();
        }

        return refined.get();
    }

    private int[] checkTopologyHealth(TerrainMesh mesh) {
        int totalVertices = 0;
        int verticesWithExcessiveEdges = 0;
        int maxEdgeCount = 0;
        int criticalVertexId = -1;

        for (TerrainVertex vertex : mesh.vertices) {
            context.checkCancelled();
            if (vertex.getObjectStatus() == TerrainObjectStatus.DELETED) {
                continue;
            }
            totalVertices++;

            List<TerrainHalfEdge> edges = vertex.getAllOutingHalfEdges(listHalfEdges);
            int edgeCount = edges.size();

            if (edgeCount > maxEdgeCount) {
                maxEdgeCount = edgeCount;
                criticalVertexId = vertex.getId();
            }

            if (edgeCount > 10) {
                verticesWithExcessiveEdges++;
                if (edgeCount > 15) {
                    log.warn("[TopologyHealth] Critical: Vertex {} has {} half-edges (threshold: 15)", vertex.getId(), edgeCount);
                }
            }
        }

        if (verticesWithExcessiveEdges > 0) {
            double corruptionRate = (double) verticesWithExcessiveEdges / totalVertices * 100.0;
            log.warn("[TopologyHealth] Detected {} vertices with >10 edges out of {} total ({:.2f}%). " + "Max edges: {} (vertex {})", verticesWithExcessiveEdges, totalVertices, String.format("%.2f", corruptionRate), maxEdgeCount, criticalVertexId);
        }

        return new int[]{totalVertices, verticesWithExcessiveEdges, maxEdgeCount};
    }

    public void refineMesh(TerrainMesh mesh, TileRange tilesRange) throws TransformException, IOException {
        refineMeshWithMemoryGuard(mesh, tilesRange);
    }

    public void refineMeshLegacy(TerrainMesh mesh, TileRange tilesRange) throws TransformException, IOException {

        double maxDiff = this.manager.getMaxDiffBetweenGeoTiffSampleAndTrianglePlane(tilesRange.getTileDepth());
        log.debug("[RefineMesh] Tile Level : {} # MaxDiff(m) : {}", tilesRange.getTileDepth(), maxDiff);

        boolean finished = false;
        int splitCount = 0;
        int maxIterations = this.manager.getTriangleRefinementMaxIterations();
        while (!finished) {
            context.checkCancelled();
            if (!this.refineMeshOneIteration(mesh, tilesRange)) {
                finished = true;
            }

            splitCount++;

            if (splitCount >= maxIterations) {
                finished = true;
            }
        }
    }

    public void refineMeshWithMemoryGuard(TerrainMesh mesh, TileRange tilesRange) throws TransformException, IOException {

        double maxDiff = this.manager.getMaxDiffBetweenGeoTiffSampleAndTrianglePlane(tilesRange.getTileDepth());
        log.debug("[RefineMesh] Starting refinement: Tile depth={}, Initial triangles={}, MaxDiff(m)={}, Max iterations={}", tilesRange.getTileDepth(), mesh.triangles.size(), maxDiff, this.manager.getTriangleRefinementMaxIterations());

        boolean finished = false;
        int splitCount = 0;
        int maxIterations = this.manager.getTriangleRefinementMaxIterations();
        int consecutiveNoProgressCount = 0;
        int previousTriangleCount = mesh.triangles.size();
        final int CONVERGENCE_WINDOW = 7;

        long previousUsedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final int MEMORY_CHECK_INTERVAL = 1;

        MemoryMonitor.MemoryState initialMemState = MemoryMonitor.checkMemory("RefineMesh-Start");
        if (initialMemState.isCritical) {
            log.error("[RefineMesh] CRITICAL memory pressure BEFORE refinement starts: free={}%. " + "Cannot proceed. Increase heap size (-Xmx).", initialMemState.getFormattedPercent());
            return;
        }

        while (!finished) {
            context.checkCancelled();

            if (splitCount % MEMORY_CHECK_INTERVAL == 0) {
                MemoryMonitor.MemoryState memState = MemoryMonitor.checkMemory("RefineMesh-Iter" + splitCount);

                if (memState.isCritical) {
                    int currentTriangleCount = mesh.triangles.size();
                    int currentVertexCount = mesh.vertices.size();

                    MemoryMonitor.MemoryMetrics metrics = new MemoryMonitor.MemoryMetrics(memState.usedMemory, currentTriangleCount, currentVertexCount);

                    log.error("[RefineMesh] STOPPING at iteration {} due to CRITICAL memory pressure: " + "free={}%, triangles={}, vertices={}, " + "memory_per_triangle={}, memory_per_vertex={}. " + "Increase heap size (-Xmx) or reduce refinement intensity to process deeper tiles.", splitCount, memState.getFormattedPercent(), currentTriangleCount, currentVertexCount, metrics.formattedBytesPerTriangle, metrics.formattedBytesPerVertex);

                    finished = true;
                    continue;
                }

                if (memState.isWarning && splitCount % 5 == 0) {
                    int currentTriangleCount = mesh.triangles.size();
                    MemoryMonitor.MemoryGrowth growth = MemoryMonitor.calculateGrowth(previousUsedMemory, memState.usedMemory, previousTriangleCount, currentTriangleCount);

                    log.warn("[RefineMesh] Memory warning at iteration {}: free={}%, " + "triangles_added={}, memory_growth={}MB, " + "triangles_total={}, vertices_total={}", splitCount, memState.getFormattedPercent(), growth.triangleDelta, String.format("%.2f", growth.growthRateMB), currentTriangleCount, mesh.vertices.size());
                }

                previousUsedMemory = memState.usedMemory;
            }

            boolean refined = this.refineMeshOneIteration(mesh, tilesRange);

            if (!refined) {
                finished = true;
                log.debug("[RefineMesh] Converged naturally after {} iterations", splitCount);
            } else {
                splitCount++;

                int currentTriangleCount = mesh.triangles.size();
                int trianglesAdded = currentTriangleCount - previousTriangleCount;

                if (splitCount % 3 == 0 || trianglesAdded <= 0) {
                    int[] health = checkTopologyHealth(mesh);
                    int totalVertices = health[0];
                    int verticesWithExcessiveEdges = health[1];
                    int maxEdgeCount = health[2];

                    if (totalVertices > 0) {
                        double corruptionRate = (double) verticesWithExcessiveEdges / totalVertices * 100.0;

                        if (corruptionRate > 5.0 || maxEdgeCount > 20) {
                            log.error("[RefineMesh] SEVERE topology corruption detected at iteration {}: " + "corruption_rate={}%, max_edges={}. " + "Stopping refinement to prevent OutOfMemoryError.", splitCount, String.format("%.2f", corruptionRate), maxEdgeCount);
                            finished = true;
                            continue;
                        }

                        if (corruptionRate > 2.0 && corruptionRate <= 5.0) {
                            log.warn("[RefineMesh] MODERATE topology corruption at iteration {}: " + "corruption_rate={}%, max_edges={}. Watching closely.", splitCount, String.format("%.2f", corruptionRate), maxEdgeCount);
                        }
                    }
                }

                if (trianglesAdded <= 0) {

                    consecutiveNoProgressCount++;
                    log.warn("[RefineMesh] Iteration {}/{} added no new triangles (current: {}, previous: {}). " + "Consecutive no-progress count: {}/{}", splitCount, maxIterations, currentTriangleCount, previousTriangleCount, consecutiveNoProgressCount, CONVERGENCE_WINDOW);

                    if (consecutiveNoProgressCount >= CONVERGENCE_WINDOW) {
                        log.error("[RefineMesh] No progress for {} consecutive iterations. " + "Stopping refinement to prevent infinite loop. " + "This may indicate topology corruption or deadlock issues.", CONVERGENCE_WINDOW);
                        finished = true;
                    }
                } else {
                    consecutiveNoProgressCount = 0;
                    log.debug("[RefineMesh] Iteration {}/{}: added {} triangles, total {}, vertices {}", splitCount, maxIterations, trianglesAdded, currentTriangleCount, mesh.vertices.size());
                }

                previousTriangleCount = currentTriangleCount;

                if (splitCount >= maxIterations) {
                    log.warn("[RefineMesh] Reached maximum iterations ({}) without full convergence. " + "Final triangle count: {}", maxIterations, currentTriangleCount);
                    finished = true;
                }
            }
        }

        log.debug("[RefineMesh] Refinement complete: {} iterations, final triangle count: {}", splitCount, mesh.triangles.size());

        MemoryMonitor.MemoryState finalMemState = MemoryMonitor.checkMemory("RefineMesh-Complete");
        MemoryMonitor.MemoryMetrics finalMetrics = new MemoryMonitor.MemoryMetrics(finalMemState.usedMemory, mesh.triangles.size(), mesh.vertices.size());

        log.debug("[RefineMesh] Final memory state: used={}, free={}%, " + "avg_memory_per_triangle={}, avg_memory_per_vertex={}", finalMemState.getUsedMemoryDisplay(), finalMemState.getFormattedPercent(), finalMetrics.formattedBytesPerTriangle, finalMetrics.formattedBytesPerVertex);
    }
}
