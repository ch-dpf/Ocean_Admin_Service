/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.util;

import com.gaia3d.terrain.structure.GeographicExtension;
import com.gaia3d.terrain.structure.TerrainMesh;
import com.gaia3d.terrain.structure.TerrainVertex;
import com.gaia3d.terrain.tile.core.TileIndices;
import com.gaia3d.terrain.tile.core.TileRange;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@UtilityClass
public class GeographicTerrainTileUtils {
    public static double getTileSizeInMetersByDepth(int depth) {
        double angDeg = GeographicTerrainTileUtils.selectTileAngleRangeByDepth(depth);
        double angRad = angDeg * Math.PI / 180.0;
        return angRad * com.gaia3d.util.CelestialBody.EARTH.getEquatorialRadius();
    }

    public static int getMaxTileDepthByPixelSizeMeters(double pixelSizeMeters) {
        int depth = 0;
        double currTileSizeMeters = pixelSizeMeters * 256.0;
        while (true) {
            double tileSizeMeters = GeographicTerrainTileUtils.getTileSizeInMetersByDepth(depth);
            if (tileSizeMeters <= currTileSizeMeters) {
                depth++;
                break;
            }
            depth++;
            if (depth > 28) {
                depth = 28;
                break;
            }
        }
        return depth;
    }

    public static void clampVerticesInToTile(TerrainMesh mesh, TileIndices tileIndices, String imaginaryType, boolean originIsLeftUp) {

        GeographicExtension geographicExtension = GeographicTerrainTileUtils.getGeographicExtentOfTileLXY(tileIndices.getL(), tileIndices.getX(), tileIndices.getY(), null, imaginaryType, originIsLeftUp);
        if (geographicExtension == null) {
            return;
        }
        double minLonDeg = geographicExtension.getMinLongitudeDeg();
        double maxLonDeg = geographicExtension.getMaxLongitudeDeg();
        double minLatDeg = geographicExtension.getMinLatitudeDeg();
        double maxLatDeg = geographicExtension.getMaxLatitudeDeg();

        List<TerrainVertex> downVertices = mesh.getDownVerticesSortedLeftToRight();
        for (TerrainVertex vertex : downVertices) {
            double latDeg = vertex.getPosition().y;
            if (latDeg != minLatDeg) {
                vertex.getPosition().y = minLatDeg;
            }
        }

        List<TerrainVertex> upVertices = mesh.getUpVerticesSortedRightToLeft();
        for (TerrainVertex vertex : upVertices) {
            double latDeg = vertex.getPosition().y;
            if (latDeg != maxLatDeg) {
                vertex.getPosition().y = maxLatDeg;
            }
        }

        List<TerrainVertex> leftVertices = mesh.getLeftVerticesSortedUpToDown();
        for (TerrainVertex vertex : leftVertices) {
            double lonDeg = vertex.getPosition().x;
            if (lonDeg != minLonDeg) {
                vertex.getPosition().x = minLonDeg;
            }
        }

        List<TerrainVertex> rightVertices = mesh.getRightVerticesSortedDownToUp();
        for (TerrainVertex vertex : rightVertices) {
            double lonDeg = vertex.getPosition().x;
            if (lonDeg != maxLonDeg) {
                vertex.getPosition().x = maxLonDeg;
            }
        }
    }

    public static double getMaxDiffBetweenGeoTiffSampleAndTrianglePlane(int depth) {
        double tileSize = GeographicTerrainTileUtils.getTileSizeInMetersByDepth(depth);
        double result;
        if (depth < 5) {
            result = tileSize * 0.01;
        } else if (depth <= 8) {
            result = tileSize * 0.015;
        } else if (depth == 9) {
            result = tileSize * 0.02;
        } else if (depth == 10) {
            result = tileSize * 0.03;
        } else if (depth == 11) {
            result = tileSize * 0.04;
        } else if (depth == 12) {
            result = tileSize * 0.05;
        } else if (depth == 13) {
            result = tileSize * 0.06;
        } else if (depth == 14) {
            result = tileSize * 0.06;
        } else if (depth == 15) {
            result = tileSize * 0.06;
        } else if (depth == 16) {
            result = tileSize * 0.065;
        } else if (depth == 17) {
            result = tileSize * 0.07;
        } else if (depth == 18) {
            result = tileSize * 0.07;
        } else {
            result = tileSize * 0.07;
        }
        return result / 4.0d;
    }

    public static double selectTileAngleRangeByDepth(int depth) {

        if (depth < 0 || depth > 28) {
            return -1.0;
        }

        return 180.0 / Math.pow(2, depth);
    }

    public static int getRefinementIterations(int depth) {
        if (depth < 0 || depth > 28) {
            return 5;
        }

        if (depth < 6) {
            return 15;
        } else if (depth < 10) {
            return 15;
        } else if (depth < 20) {
            return 15;
        } else {
            return 15;
        }
    }

    public static TileIndices selectTileIndices(int depth, double longitude, double latitude, TileIndices out, boolean originIsLeftUp) {

        if (out == null) {
            out = new TileIndices();
        }

        int tilesX = 1 << (depth + 1);
        int tilesY = 1 << depth;

        double angRangeLon = 360.0 / tilesX;
        double angRangeLat = 180.0 / tilesY;

        int x = (int) Math.floor((longitude + 180.0) / angRangeLon);
        int y;

        if (originIsLeftUp) {
            y = (int) Math.floor((90.0 - latitude) / angRangeLat);
        } else {
            y = (int) Math.floor((latitude + 90.0) / angRangeLat);
        }

        x = Math.max(0, Math.min(x, tilesX - 1));
        y = Math.max(0, Math.min(y, tilesY - 1));

        out.set(x, y, depth);
        return out;
    }

    public static void selectTileIndicesArray(int depth, double minLon, double maxLon, double minLat, double maxLat, TileRange tilesRange, boolean originIsLeftUp) {

        TileIndices leftDownTileName = GeographicTerrainTileUtils.selectTileIndices(depth, minLon, minLat, null, originIsLeftUp);
        TileIndices rightDownTileName = GeographicTerrainTileUtils.selectTileIndices(depth, maxLon, minLat, null, originIsLeftUp);
        TileIndices rightUpTileName = GeographicTerrainTileUtils.selectTileIndices(depth, maxLon, maxLat, null, originIsLeftUp);

        int minX = leftDownTileName.getX();
        int maxX = rightDownTileName.getX();
        int maxY = leftDownTileName.getY();
        int minY = rightUpTileName.getY();

        if (depth == 0) {
            if (tilesRange != null) {
                tilesRange.setTileDepth(depth);
                tilesRange.setMinTileX(minX);
                tilesRange.setMaxTileX(maxX);
                tilesRange.setMinTileY(minY);
                tilesRange.setMaxTileY(maxY);
            }
            return;
        }

        double xMin = -180.0;
        double yMin = -90.0;

        double angRange = GeographicTerrainTileUtils.selectTileAngleRangeByDepth(depth);
        int xIndexMax = (int) Math.round((maxLon - xMin) / angRange);
        int yIndexMax = (int) Math.round((maxLat - yMin) / angRange);

        int xIndexMin = (int) Math.round((minLon - xMin) / angRange);
        int yIndexMin = (int) Math.round((minLat - yMin) / angRange);

        if (!originIsLeftUp) {
            maxY = rightUpTileName.getY();
            minY = leftDownTileName.getY();
        }

        if (maxX < xIndexMax) {
            maxX = xIndexMax;
        }

        if (maxY < yIndexMax) {
            maxY = yIndexMax;
        }

        if (minX > xIndexMin) {
            minX = xIndexMin;
        }

        if (minY > yIndexMin) {
            minY = yIndexMin;
        }

        int maxValidX = (int) Math.pow(2, depth + 1) - 1;
        int maxValidY = (int) Math.pow(2, depth) - 1;
        if (maxX > maxValidX) {maxX = maxValidX;}
        if (maxY > maxValidY) {maxY = maxValidY;}

        if (tilesRange != null) {
            tilesRange.setTileDepth(depth);
            tilesRange.setMinTileX(minX);
            tilesRange.setMaxTileX(maxX);
            tilesRange.setMinTileY(minY);
            tilesRange.setMaxTileY(maxY);
        }
    }

    public static String getTileFileName(int X, int Y, int L) {
        return "L" + L + "_X" + X + "_Y" + Y + ".til";
    }

    public static String getTileFolderNameL(int L) {
        return "L" + L;
    }

    public static String getTileFolderNameX(int X) {
        return "X" + X;
    }

    public static String getTileFilePath(int X, int Y, int L) {
        return getTileFolderNameL(L) + File.separator + getTileFolderNameX(X) + File.separator + getTileFileName(X, Y, L);
    }

    public static List<TileRange> subDivideTileRange(TileRange tilesRange, int maxCol, int maxRow, List<TileRange> resultSubDividedTilesRanges) {
        if (resultSubDividedTilesRanges == null) {
            resultSubDividedTilesRanges = new ArrayList<>();
        }

        int colsCount = tilesRange.getMaxTileX() - tilesRange.getMinTileX() + 1;
        int rowsCount = tilesRange.getMaxTileY() - tilesRange.getMinTileY() + 1;

        if (colsCount <= maxCol && rowsCount <= maxRow) {
            resultSubDividedTilesRanges.add(tilesRange);
            return resultSubDividedTilesRanges;
        }

        int colsSubDividedCount = 1;
        int rowsSubDividedCount = 1;

        if (colsCount > maxCol) {
            colsSubDividedCount = (int) Math.ceil((double) colsCount / (double) maxCol);
        }

        if (rowsCount > maxRow) {
            rowsSubDividedCount = (int) Math.ceil((double) rowsCount / (double) maxRow);
        }

        double colsSubDividedSize = (double) colsCount / (double) colsSubDividedCount;
        double rowsSubDividedSize = (double) rowsCount / (double) rowsSubDividedCount;

        for (int i = 0; i < colsSubDividedCount; i++) {
            for (int j = 0; j < rowsSubDividedCount; j++) {
                TileRange subDividedTilesRange = new TileRange();
                subDividedTilesRange.setTileDepth(tilesRange.getTileDepth());
                subDividedTilesRange.setMinTileX(tilesRange.getMinTileX() + (int) (i * colsSubDividedSize));
                subDividedTilesRange.setMaxTileX(tilesRange.getMinTileX() + (int) ((i + 1) * colsSubDividedSize) - 1);
                subDividedTilesRange.setMinTileY(tilesRange.getMinTileY() + (int) (j * rowsSubDividedSize));
                subDividedTilesRange.setMaxTileY(tilesRange.getMinTileY() + (int) ((j + 1) * rowsSubDividedSize) - 1);

                resultSubDividedTilesRanges.add(subDividedTilesRange);
            }
        }

        return resultSubDividedTilesRanges;
    }

    public static boolean isValidTileIndices(int L, int X, int Y) {

        double angDeg = GeographicTerrainTileUtils.selectTileAngleRangeByDepth(L);

        int numTilesX = (int) (360.0 / angDeg);
        int numTilesY = (int) (180.0 / angDeg);

        if (X < 0 || X >= numTilesX) {
            return false;
        }

        return Y >= 0 && Y < numTilesY;
    }

    public static GeographicExtension getGeographicExtentOfTileLXY(int L, int X, int Y, GeographicExtension resultGeoExtend, String imageryType, boolean originIsLeftUp) {
        if (resultGeoExtend == null) {
            resultGeoExtend = new GeographicExtension();
        }

        if (imageryType.equals("CRS84")) {
            double angRange = GeographicTerrainTileUtils.selectTileAngleRangeByDepth(L);
            double minLon = angRange * (double) X - 180.0;
            double maxLon = angRange * ((double) X + 1.0) - 180.0;
            double minLat = 90.0 - angRange * ((double) Y + 1.0);
            double maxLat = 90.0 - angRange * ((double) Y);

            if (!originIsLeftUp) {
                minLat = -90.0 + angRange * ((double) Y);
                maxLat = -90.0 + angRange * ((double) Y + 1.0);
            }

            resultGeoExtend.setDegrees(minLon, minLat, 0, maxLon, maxLat, 0);
            return resultGeoExtend;
        } else if (imageryType.equals("WEB_MERCATOR")) {
            double webMercatorMaxLatRad = 1.4844222297453324;

            double numCols = Math.pow(2, L);
            double numRows = numCols;

            double lonAngDegRange = 360.0 / numCols;

            double M_PI = Math.PI;
            double M_E = Math.E;
            double maxMercatorY = M_PI;
            double minMercatorY = -M_PI;
            double maxLadRad = webMercatorMaxLatRad;
            double minLadRad = -webMercatorMaxLatRad;
            double midLatRad;
            double midLatRadMercator;
            double y_ratio = (Y + 0.0005) / numRows;
            int currL = 0;
            boolean finished = false;
            while (!finished && currL <= 22) {
                if (currL == L) {
                    double minLongitude = lonAngDegRange * (double) X - 180.0;
                    double maxLongitude = minLongitude + lonAngDegRange;
                    double minLatitude = minLadRad * 180.0 / M_PI;
                    double maxLatitude = maxLadRad * 180.0 / M_PI;

                    resultGeoExtend.setDegrees(minLongitude, minLatitude, 0, maxLongitude, maxLatitude, 0);
                    finished = true;
                } else {
                    double midMercatorY = (maxMercatorY + minMercatorY) / 2.0;
                    midLatRad = 2.0 * Math.atan(Math.pow(M_E, midMercatorY)) - M_PI / 2.0;
                    double midLatRatio = (M_PI - midMercatorY) / (M_PI - (-M_PI));

                    if (midLatRatio > y_ratio) {

                        minLadRad = midLatRad;
                        minMercatorY = midMercatorY;
                    } else {

                        maxLadRad = midLatRad;
                        maxMercatorY = midMercatorY;

                    }
                }

                currL++;
            }
            return resultGeoExtend;
        }
        return null;
    }
}
