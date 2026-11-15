/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.util;

import com.gaia3d.terrain.tile.generation.TerrainTilingContext;
import com.gaia3d.terrain.structure.*;
import com.gaia3d.terrain.tile.core.TileIndices;
import com.gaia3d.terrain.tile.generation.TerrainTilesetGenerator;
import com.gaia3d.terrain.types.TerrainHalfEdgeType;
import com.gaia3d.terrain.types.TerrainObjectStatus;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.joml.Vector3d;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
@UtilityClass
public class TerrainMeshUtils {
    public static List<TerrainHalfEdge> getHalfEdgesOfTriangles(List<TerrainTriangle> triangles, List<TerrainHalfEdge> resultHalfEdges, List<TerrainHalfEdge> listHalfEdges) {
        if (resultHalfEdges == null) {
            resultHalfEdges = new ArrayList<>();
        }
        listHalfEdges.clear();
        for (TerrainTriangle triangle : triangles) {
            triangle.getHalfEdge().getHalfEdgesLoop(listHalfEdges);
            resultHalfEdges.addAll(listHalfEdges);
            listHalfEdges.clear();
        }
        return resultHalfEdges;
    }

    public static List<TerrainVertex> getVerticesOfTriangles(List<TerrainTriangle> triangles) {
        List<TerrainVertex> resultVertices = new ArrayList<>();
        HashMap<TerrainVertex, Integer> map_vertices = new HashMap<>();
        List<TerrainVertex> listVertices = new ArrayList<>();
        List<TerrainHalfEdge> listHalfEdges = new ArrayList<>();
        for (TerrainTriangle triangle : triangles) {
            listVertices.clear();
            listHalfEdges.clear();
            listVertices = triangle.getVertices(listVertices, listHalfEdges);
            for (TerrainVertex vertex : listVertices) {
                if (!map_vertices.containsKey(vertex)) {
                    map_vertices.put(vertex, 1);
                    resultVertices.add(vertex);
                }
            }
        }
        return resultVertices;
    }

    public static void getSeparatedMeshes(TerrainMesh bigMesh, List<TerrainMesh> resultSeparatedMeshes, boolean originIsLeftUp) {

        List<TerrainTriangle> triangles = bigMesh.triangles;
        HashMap<Long, List<TerrainTriangle>> map_triangles = new HashMap<>();
        for (TerrainTriangle triangle : triangles) {
            if (triangle.getOwnerTileIndices() != null) {
                TileIndices tileIndices = triangle.getOwnerTileIndices();
                long tileKey = tileIndices.toCacheKey();
                List<TerrainTriangle> trianglesList = map_triangles.get(tileKey);
                if (trianglesList == null) {
                    trianglesList = new ArrayList<>();
                    map_triangles.put(tileKey, trianglesList);
                }
                trianglesList.add(triangle);
            } else {

                log.info("Error: triangle has not ownerTile_tileIndices.");
            }
        }

        for (Long tileKey : map_triangles.keySet()) {
            List<TerrainTriangle> trianglesList = map_triangles.get(tileKey);

            TerrainMesh separatedMesh = new TerrainMesh();
            separatedMesh.triangles = trianglesList;
            TileIndices tileIndices = trianglesList.get(0).getOwnerTileIndices();
            TileIndices L_tileIndices = tileIndices.getLeftTileIndices(originIsLeftUp);
            TileIndices R_tileIndices = tileIndices.getRightTileIndices(originIsLeftUp);
            TileIndices U_tileIndices = tileIndices.getUpTileIndices(originIsLeftUp);
            TileIndices D_tileIndices = tileIndices.getDownTileIndices(originIsLeftUp);

            List<TerrainHalfEdge> listHalfEdges = new ArrayList<>();
            List<TerrainHalfEdge> halfEdges = new ArrayList<>();
            halfEdges = TerrainMeshUtils.getHalfEdgesOfTriangles(trianglesList, halfEdges, listHalfEdges);

            int halfEdges_count = halfEdges.size();
            for (int i = 0; i < halfEdges_count; i++) {
                TerrainHalfEdge halfEdge = halfEdges.get(i);
                TerrainHalfEdge twin = halfEdge.getTwin();
                if (twin != null) {
                    TerrainTriangle twins_triangle = twin.getTriangle();
                    if (twins_triangle != null) {
                        TileIndices twinsTriangleTileIndices = twins_triangle.getOwnerTileIndices();
                        if (twinsTriangleTileIndices != null && !twinsTriangleTileIndices.isCoincident(tileIndices)) {

                            halfEdge.setTwin(null);
                            twin.setTwin(null);

                            if (twinsTriangleTileIndices.isCoincident(L_tileIndices)) {
                                halfEdge.setType(TerrainHalfEdgeType.LEFT);
                                twin.setType(TerrainHalfEdgeType.RIGHT);
                            } else if (twinsTriangleTileIndices.isCoincident(R_tileIndices)) {
                                halfEdge.setType(TerrainHalfEdgeType.RIGHT);
                                twin.setType(TerrainHalfEdgeType.LEFT);
                            } else if (twinsTriangleTileIndices.isCoincident(U_tileIndices)) {
                                halfEdge.setType(TerrainHalfEdgeType.UP);
                                twin.setType(TerrainHalfEdgeType.DOWN);
                            } else if (twinsTriangleTileIndices.isCoincident(D_tileIndices)) {
                                halfEdge.setType(TerrainHalfEdgeType.DOWN);
                                twin.setType(TerrainHalfEdgeType.UP);
                            }
                        }
                    }
                }
            }

            separatedMesh.halfEdges = halfEdges;
            separatedMesh.vertices = TerrainMeshUtils.getVerticesOfTriangles(trianglesList);
            resultSeparatedMeshes.add(separatedMesh);
        }

    }

    public static void save4ChildrenMeshes(TerrainMesh mesh, TerrainTilesetGenerator manager, TerrainTilingContext context) {
        TerrainTriangle triangle = mesh.triangles.get(0);
        TileIndices tileIndices = triangle.getOwnerTileIndices();

        boolean originIsLeftUp = manager.isOriginIsLeftUp();
        String imageryType = manager.getImaginaryType();

        TileIndices childLightUpTileIndices = tileIndices.getChildLeftUpTileIndices(originIsLeftUp);
        TileIndices childRightUpTileIndices = tileIndices.getChildRightUpTileIndices(originIsLeftUp);
        TileIndices childLeftDownTileIndices = tileIndices.getChildLeftDownTileIndices(originIsLeftUp);
        TileIndices childRightDownTileIndices = tileIndices.getChildRightDownTileIndices(originIsLeftUp);

        GeographicExtension geoExtension = GeographicTerrainTileUtils.getGeographicExtentOfTileLXY(tileIndices.getL(), tileIndices.getX(), tileIndices.getY(), null, imageryType, originIsLeftUp);
        double midLonDeg = geoExtension.getMidLongitudeDeg();
        double midLatDeg = geoExtension.getMidLatitudeDeg();
        List<TerrainTriangle> triangles = mesh.triangles;

        List<TerrainVertex> listVertices = new ArrayList<>();
        List<TerrainHalfEdge> listHalfEdges = new ArrayList<>();

        for (TerrainTriangle gaiaTriangle : triangles) {
            triangle = gaiaTriangle;

            if (triangle.getObjectStatus() == TerrainObjectStatus.DELETED) {
                continue;
            }

            listVertices.clear();
            listHalfEdges.clear();
            Vector3d barycenter = triangle.getBarycenter(listVertices, listHalfEdges);
            if (barycenter.x < midLonDeg) {
                if (barycenter.y < midLatDeg) {

                    triangle.setOwnerTileIndices(childLeftDownTileIndices);
                } else {

                    triangle.setOwnerTileIndices(childLightUpTileIndices);
                }
            } else {
                if (barycenter.y < midLatDeg) {

                    triangle.setOwnerTileIndices(childRightDownTileIndices);
                } else {

                    triangle.setOwnerTileIndices(childRightUpTileIndices);
                }
            }
        }

        List<TerrainMesh> childMeshes = new ArrayList<>();
        TerrainMeshUtils.getSeparatedMeshes(mesh, childMeshes, manager.isOriginIsLeftUp());

        if (childMeshes.size() != 4) {
            log.info("Info: childMeshes.size() != 4 : tile indices : " + tileIndices.getString());
        }

        for (TerrainMesh childMesh : childMeshes) {
            triangle = childMesh.triangles.get(0);
            TileIndices childTileIndices = triangle.getOwnerTileIndices();

            GeographicTerrainTileUtils.clampVerticesInToTile(childMesh, childTileIndices, manager.getImaginaryType(), manager.originIsLeftUp());

            String tileTempDirectory = context.getTileTempPath();
            String childTileFilePath = GeographicTerrainTileUtils.getTileFilePath(childTileIndices.getX(), childTileIndices.getY(), childTileIndices.getL());
            String childTileFullPath = tileTempDirectory + File.separator + childTileFilePath;

            try {

                childMesh.saveFile(childTileFullPath);
            } catch (IOException e) {
                log.error("Error:", e);
                return;
            }
        }
    }
}
