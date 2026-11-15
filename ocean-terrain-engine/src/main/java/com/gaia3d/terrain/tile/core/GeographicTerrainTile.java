/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.core;

import com.gaia3d.io.BigEndianDataInputStream;
import com.gaia3d.io.BigEndianDataOutputStream;
import com.gaia3d.terrain.structure.*;
import com.gaia3d.terrain.tile.elevation.TerrainElevationModeler;
import com.gaia3d.terrain.tile.generation.TerrainTilesetGenerator;
import com.gaia3d.terrain.types.TerrainHalfEdgeType;
import com.gaia3d.terrain.types.TerrainObjectStatus;
import com.gaia3d.util.FileUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.operation.TransformException;
import org.joml.Vector3d;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Slf4j
public class GeographicTerrainTile {
    private TerrainTilesetGenerator manager;
    private GeographicTerrainTile parentTile;
    private TileIndices tileIndices = null;
    private GeographicExtension geographicExtension = null;
    private TerrainMesh mesh = null;

    private GeographicTerrainTile[] neighborTiles = new GeographicTerrainTile[8];
    private GeographicTerrainTile[] childTiles = new GeographicTerrainTile[4];

    private List<TerrainVertex> listVertices = new ArrayList<>();
    private List<TerrainHalfEdge> listHalfEdges = new ArrayList<>();

    public GeographicTerrainTile(GeographicTerrainTile parentTile, TerrainTilesetGenerator manager) {
        this.parentTile = parentTile;
        this.manager = manager;
    }

    public void deleteObjects() {
        this.parentTile = null;
        this.tileIndices = null;
        if (this.geographicExtension != null) {
            this.geographicExtension.deleteObjects();
            this.geographicExtension = null;
        }
        if (this.mesh != null) {
            this.mesh.deleteObjects();
            this.mesh = null;
        }
        this.manager = null;
        this.neighborTiles = null;
        this.childTiles = null;
    }

    public void saveFile(TerrainMesh mesh, String filePath) throws IOException {
        String foldersPath = FileUtils.removeFileNameFromPath(filePath);
        FileUtils.createAllFoldersIfNoExist(foldersPath);
        try (BigEndianDataOutputStream dataOutputStream = new BigEndianDataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath)))) {
            mesh.saveDataOutputStream(dataOutputStream);
        }
    }

    public void loadFile(String filePath) throws IOException {
        try (BigEndianDataInputStream dataInputStream = new BigEndianDataInputStream(new BufferedInputStream(new FileInputStream(filePath)))) {
            this.mesh = new TerrainMesh();
            this.mesh.loadDataInputStream(dataInputStream);
        }
    }

    public void createInitialMesh() throws TransformException, IOException {

        this.mesh = new TerrainMesh();

        TerrainVertex vertexLD = this.mesh.newVertex();
        TerrainVertex vertexRD = this.mesh.newVertex();
        TerrainVertex vertexRU = this.mesh.newVertex();
        TerrainVertex vertexLU = this.mesh.newVertex();

        TerrainElevationModeler terrainElevationModeler = this.manager.getTerrainElevationModeler();

        double minLonDeg = this.geographicExtension.getMinLongitudeDeg();
        double minLatDeg = this.geographicExtension.getMinLatitudeDeg();
        double maxLonDeg = this.geographicExtension.getMaxLongitudeDeg();
        double maxLatDeg = this.geographicExtension.getMaxLatitudeDeg();

        byte[] intersectionType = {0};

        double elevMinLonMinLat = terrainElevationModeler.sampleBilinearElevation(this.tileIndices, this.manager, minLonDeg, minLatDeg, intersectionType);
        double elevMaxLonMinLat = terrainElevationModeler.sampleBilinearElevation(this.tileIndices, this.manager, maxLonDeg, minLatDeg, intersectionType);
        double elevMaxLonMaxLat = terrainElevationModeler.sampleBilinearElevation(this.tileIndices, this.manager, maxLonDeg, maxLatDeg, intersectionType);
        double elevMinLonMaxLat = terrainElevationModeler.sampleBilinearElevation(this.tileIndices, this.manager, minLonDeg, maxLatDeg, intersectionType);

        vertexLD.setPosition(new Vector3d(minLonDeg, minLatDeg, elevMinLonMinLat));
        vertexRD.setPosition(new Vector3d(maxLonDeg, minLatDeg, elevMaxLonMinLat));
        vertexRU.setPosition(new Vector3d(maxLonDeg, maxLatDeg, elevMaxLonMaxLat));
        vertexLU.setPosition(new Vector3d(minLonDeg, maxLatDeg, elevMinLonMaxLat));

        TerrainTriangle triangle1 = this.mesh.newTriangle();
        TerrainTriangle triangle2 = this.mesh.newTriangle();

        TerrainHalfEdge halfEdgeT1V1 = this.mesh.newHalfEdge();
        TerrainHalfEdge halfEdgeT1V2 = this.mesh.newHalfEdge();
        TerrainHalfEdge halfEdgeT1V3 = this.mesh.newHalfEdge();

        halfEdgeT1V1.setStartVertex(vertexLD);
        halfEdgeT1V1.setType(TerrainHalfEdgeType.DOWN);
        halfEdgeT1V2.setStartVertex(vertexRD);
        halfEdgeT1V2.setType(TerrainHalfEdgeType.RIGHT);
        halfEdgeT1V3.setStartVertex(vertexRU);
        halfEdgeT1V3.setType(TerrainHalfEdgeType.INTERIOR);

        List<TerrainHalfEdge> halfEdges_T1 = new ArrayList<>();
        halfEdges_T1.add(halfEdgeT1V1);
        halfEdges_T1.add(halfEdgeT1V2);
        halfEdges_T1.add(halfEdgeT1V3);

        TerrainHalfEdgeUtils.concatenateHalfEdgesLoop(halfEdges_T1);

        TerrainHalfEdge halfEdgeT2V1 = this.mesh.newHalfEdge();
        TerrainHalfEdge halfEdgeT2V2 = this.mesh.newHalfEdge();
        TerrainHalfEdge halfEdgeT2V3 = this.mesh.newHalfEdge();

        halfEdgeT2V1.setStartVertex(vertexLD);
        halfEdgeT2V1.setType(TerrainHalfEdgeType.INTERIOR);
        halfEdgeT2V2.setStartVertex(vertexRU);
        halfEdgeT2V2.setType(TerrainHalfEdgeType.UP);
        halfEdgeT2V3.setStartVertex(vertexLU);
        halfEdgeT2V3.setType(TerrainHalfEdgeType.LEFT);

        List<TerrainHalfEdge> halfEdgesT2 = new ArrayList<>();
        halfEdgesT2.add(halfEdgeT2V1);
        halfEdgesT2.add(halfEdgeT2V2);
        halfEdgesT2.add(halfEdgeT2V3);

        TerrainHalfEdgeUtils.concatenateHalfEdgesLoop(halfEdgesT2);

        halfEdgeT1V3.setTwin(halfEdgeT2V1);

        triangle1.setHalfEdge(halfEdgeT1V1);
        triangle2.setHalfEdge(halfEdgeT2V1);

        triangle1.getOwnerTileIndices().copyFrom(this.tileIndices);
        triangle2.getOwnerTileIndices().copyFrom(this.tileIndices);

        this.refineMeshInitial(this.mesh);

        this.mesh.setObjectsIdInList();

    }

    private boolean refineMeshOneIterationInitial(TerrainMesh mesh) throws TransformException, IOException {

        boolean refined = false;
        int trianglesCount = mesh.triangles.size();
        log.debug("[RefineMesh] Triangles Count : {}", trianglesCount);
        boolean isModifyProcess = false;
        for (int i = 0; i < trianglesCount; i++) {
            TerrainTriangle triangle = mesh.triangles.get(i);

            if (triangle.getObjectStatus() == TerrainObjectStatus.DELETED) {
                continue;
            }

            log.debug("[RefineMesh] FAST-Check : TRIANGLE IS BIG FOR THE TILE DEPTH");
            this.manager.getTriangleList().clear();
            this.listHalfEdges.clear();
            mesh.splitTriangle(triangle, this.manager.getTerrainElevationModeler(), this.manager.getTriangleList(), this.listHalfEdges, isModifyProcess, null);
            this.listHalfEdges.clear();

            if (!this.manager.getTriangleList().isEmpty()) {
                refined = true;
            }
        }

        this.manager.getTriangleList().clear();
        if (refined) {
            mesh.removeDeletedObjects();
            mesh.setObjectsIdInList();
        }

        return refined;
    }

    public void refineMeshInitial(TerrainMesh mesh) throws TransformException, IOException {
        boolean finished = false;
        int splitCount = 0;
        int maxIterations = 3;
        if (this.tileIndices.getL() < 10) {
            maxIterations = 4;
        }

        while (!finished) {

            if (!this.refineMeshOneIterationInitial(mesh)) {
                finished = true;
            }

            splitCount++;

            if (splitCount >= maxIterations) {
                finished = true;
            }
        }
    }

}
