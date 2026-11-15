/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.custom;

import com.gaia3d.terrain.structure.GeographicExtension;
import com.gaia3d.terrain.tile.core.TileRange;
import com.gaia3d.terrain.tile.geotiff.TileRangeIntersectionType;
import com.gaia3d.terrain.util.GeographicTerrainTileUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Slf4j
public class AvailableTileSet {
    private final Map<Integer, List<TileRange>> mapDepthAvailableTileRanges;

    public AvailableTileSet() {
        mapDepthAvailableTileRanges = new HashMap<>();
    }

    public List<TileRange> getAvailableTileRangesAtDepth(int depth) {
        return mapDepthAvailableTileRanges.get(depth);
    }

    public int getMaxAvailableDepth() {
        return mapDepthAvailableTileRanges.keySet().stream().max(Integer::compareTo).orElse(-1);
    }

    public void deleteTileRangesOverDepth(int maxDepth) {
        mapDepthAvailableTileRanges.keySet().removeIf(depth -> depth > maxDepth);
    }

    public void addAvailableExtensions(double pixelSizeMeters, GeographicExtension extension) {
        int maxDepth = GeographicTerrainTileUtils.getMaxTileDepthByPixelSizeMeters(pixelSizeMeters);
        boolean originIsLeftUp = false;
        for (int depth = 0; depth <= maxDepth; depth++) {
            List<TileRange> tileRanges = mapDepthAvailableTileRanges.computeIfAbsent(depth, k -> new java.util.ArrayList<>());

            GeographicExtension extensionCopy = new GeographicExtension();
            extensionCopy.copyFrom(extension);
            double minLon = extension.getMinLongitudeDeg();
            double maxLon = extension.getMaxLongitudeDeg();
            double minLat = extension.getMinLatitudeDeg();
            double maxLat = extension.getMaxLatitudeDeg();
            TileRange tilesRange = new TileRange();
            GeographicTerrainTileUtils.selectTileIndicesArray(depth, minLon, maxLon, minLat, maxLat, tilesRange, originIsLeftUp);

            tilesRange = tilesRange.expand(1);
            tileRanges.add(tilesRange);
        }
    }

    public void recombineTileRanges() {

        for (Integer depth : mapDepthAvailableTileRanges.keySet()) {
            List<TileRange> tileRanges = mapDepthAvailableTileRanges.get(depth);
            List<TileRange> noIntersectedTileRanges = recombineTileRanges(tileRanges);
            mapDepthAvailableTileRanges.put(depth, noIntersectedTileRanges);
        }
    }

    private List<TileRange> recombineTileRanges(List<TileRange> tileRanges) {
        List<TileRange> noIntersectedTileRanges = new java.util.ArrayList<>(tileRanges);
        int tileRangesCount = noIntersectedTileRanges.size();
        if (tileRangesCount <= 1) {
            return noIntersectedTileRanges;
        }

        for (int i = 0; i < noIntersectedTileRanges.size(); i++) {
            TileRange tileRangeA = noIntersectedTileRanges.get(i);
            for (int j = i + 1; j < noIntersectedTileRanges.size(); j++) {
                TileRange tileRangeB = noIntersectedTileRanges.get(j);

                if (tileRangeA.intersects(tileRangeB)) {
                    TileRangeIntersectionType tileRangeIntersectionType = tileRangeA.getIntersectionType(tileRangeB);

                    if (tileRangeIntersectionType == TileRangeIntersectionType.PARTIAL_OVERLAP || tileRangeIntersectionType == TileRangeIntersectionType.A_CONTAINS_B || tileRangeIntersectionType == TileRangeIntersectionType.B_CONTAINS_A || tileRangeIntersectionType == TileRangeIntersectionType.HORIZONTAL_FULL_TOUCHING || tileRangeIntersectionType == TileRangeIntersectionType.VERTICAL_FULL_TOUCHING) {

                        tileRangeA.union(tileRangeB);
                        noIntersectedTileRanges.remove(tileRangeB);

                        i = -1;
                        break;
                    } else if (tileRangeIntersectionType == TileRangeIntersectionType.A_CONTAINS_2_B_0 || tileRangeIntersectionType == TileRangeIntersectionType.A_CONTAINS_2_B_1) {

                        List<TileRange> splitTileRanges = split2ZonesTileRange(tileRangeA, tileRangeB, tileRangeIntersectionType);
                        noIntersectedTileRanges.remove(tileRangeA);
                        noIntersectedTileRanges.remove(tileRangeB);
                        noIntersectedTileRanges.addAll(splitTileRanges);
                        i = -1;
                        break;
                    } else if (tileRangeIntersectionType == TileRangeIntersectionType.B_CONTAINS_2_A_0 || tileRangeIntersectionType == TileRangeIntersectionType.B_CONTAINS_2_A_1) {

                        List<TileRange> splitTileRanges = split2ZonesTileRange(tileRangeB, tileRangeA, tileRangeIntersectionType);
                        noIntersectedTileRanges.remove(tileRangeA);
                        noIntersectedTileRanges.remove(tileRangeB);
                        noIntersectedTileRanges.addAll(splitTileRanges);
                        i = -1;
                        break;
                    } else if (tileRangeIntersectionType == TileRangeIntersectionType.A_CONTAINS_1_B_1) {

                        List<TileRange> splitTileRanges = split3ZonesTileRange(tileRangeA, tileRangeB, tileRangeIntersectionType);
                        noIntersectedTileRanges.remove(tileRangeA);
                        noIntersectedTileRanges.remove(tileRangeB);
                        noIntersectedTileRanges.addAll(splitTileRanges);
                        i = -1;
                        break;
                    }

                } else {

                    TileRangeIntersectionType tileRangeIntersectionType = tileRangeA.getIntersectionType(tileRangeB);
                    if (tileRangeIntersectionType == TileRangeIntersectionType.HORIZONTAL_FULL_TOUCHING || tileRangeIntersectionType == TileRangeIntersectionType.VERTICAL_FULL_TOUCHING) {

                        tileRangeA.union(tileRangeB);
                        noIntersectedTileRanges.remove(tileRangeB);

                        i = -1;
                        break;
                    }
                }
            }
        }

        return noIntersectedTileRanges;
    }

    private List<TileRange> split3ZonesTileRange(TileRange tileRangeBig, TileRange tileRangeSmall, TileRangeIntersectionType intersectionType) {

        if (intersectionType != TileRangeIntersectionType.A_CONTAINS_1_B_1) {
            throw new IllegalArgumentException("Invalid intersection type for split3ZonesTileRange: " + intersectionType);
        }

        List<TileRange> resultTileRanges = new java.util.ArrayList<>();
        resultTileRanges.add(tileRangeBig);

        if (tileRangeSmall.getMinTileX() < tileRangeBig.getMinTileX()) {
            if (tileRangeSmall.getMinTileY() < tileRangeBig.getMinTileY()) {

                int bigMinX = tileRangeBig.getMinTileX();
                int leftX = bigMinX - 1;
                int rightX = bigMinX;
                List<TileRange> splitTileRangesX = tileRangeSmall.splitTileRangeByX(leftX, rightX);

                int bigMinY = tileRangeBig.getMinTileY();
                int bottomY = bigMinY - 1;
                int topY = bigMinY;
                for (TileRange splitTileRangeX : splitTileRangesX) {
                    if (splitTileRangeX.intersects(tileRangeBig)) {
                        List<TileRange> splitTileRangesY = splitTileRangeX.splitTileRangeByY(bottomY, topY);

                        for (TileRange splitTileRangeY : splitTileRangesY) {
                            if (!splitTileRangeY.intersects(tileRangeBig)) {
                                resultTileRanges.add(splitTileRangeY);
                            }
                        }
                    } else {
                        resultTileRanges.add(splitTileRangeX);
                    }
                }
            } else if (tileRangeSmall.getMaxTileY() > tileRangeBig.getMaxTileY()) {

                int bigMinX = tileRangeBig.getMinTileX();
                int leftX = bigMinX - 1;
                int rightX = bigMinX;
                List<TileRange> splitTileRangesX = tileRangeSmall.splitTileRangeByX(leftX, rightX);

                int bigMaxY = tileRangeBig.getMaxTileY();
                int bottomY = bigMaxY;
                int topY = bigMaxY + 1;
                for (TileRange splitTileRangeX : splitTileRangesX) {
                    if (splitTileRangeX.intersects(tileRangeBig)) {
                        List<TileRange> splitTileRangesY = splitTileRangeX.splitTileRangeByY(bottomY, topY);

                        for (TileRange splitTileRangeY : splitTileRangesY) {
                            if (!splitTileRangeY.intersects(tileRangeBig)) {
                                resultTileRanges.add(splitTileRangeY);
                            }
                        }
                    } else {
                        resultTileRanges.add(splitTileRangeX);
                    }
                }
            }
        } else if (tileRangeSmall.getMaxTileX() > tileRangeBig.getMaxTileX()) {
            if (tileRangeSmall.getMinTileY() < tileRangeBig.getMinTileY()) {

                int bigMaxX = tileRangeBig.getMaxTileX();
                int leftX = bigMaxX;
                int rightX = bigMaxX + 1;
                List<TileRange> splitTileRangesX = tileRangeSmall.splitTileRangeByX(leftX, rightX);

                int bigMinY = tileRangeBig.getMinTileY();
                int bottomY = bigMinY - 1;
                int topY = bigMinY;
                for (TileRange splitTileRangeX : splitTileRangesX) {
                    if (splitTileRangeX.intersects(tileRangeBig)) {
                        List<TileRange> splitTileRangesY = splitTileRangeX.splitTileRangeByY(bottomY, topY);

                        for (TileRange splitTileRangeY : splitTileRangesY) {
                            if (!splitTileRangeY.intersects(tileRangeBig)) {
                                resultTileRanges.add(splitTileRangeY);
                            }
                        }
                    } else {
                        resultTileRanges.add(splitTileRangeX);
                    }
                }
            } else if (tileRangeSmall.getMaxTileY() > tileRangeBig.getMaxTileY()) {

                int bigMaxX = tileRangeBig.getMaxTileX();
                int leftX = bigMaxX;
                int rightX = bigMaxX + 1;
                List<TileRange> splitTileRangesX = tileRangeSmall.splitTileRangeByX(leftX, rightX);

                int bigMaxY = tileRangeBig.getMaxTileY();
                int bottomY = bigMaxY;
                int topY = bigMaxY + 1;
                for (TileRange splitTileRangeX : splitTileRangesX) {
                    if (splitTileRangeX.intersects(tileRangeBig)) {
                        List<TileRange> splitTileRangesY = splitTileRangeX.splitTileRangeByY(bottomY, topY);

                        for (TileRange splitTileRangeY : splitTileRangesY) {
                            if (!splitTileRangeY.intersects(tileRangeBig)) {
                                resultTileRanges.add(splitTileRangeY);
                            }
                        }
                    } else {
                        resultTileRanges.add(splitTileRangeX);
                    }
                }
            }
        }
        return resultTileRanges;
    }

    private List<TileRange> split2ZonesTileRange(TileRange tileRangeBig, TileRange tileRangeSmall, TileRangeIntersectionType intersectionType) {

        if (intersectionType != TileRangeIntersectionType.A_CONTAINS_2_B_0 && intersectionType != TileRangeIntersectionType.A_CONTAINS_2_B_1 && intersectionType != TileRangeIntersectionType.B_CONTAINS_2_A_0 && intersectionType != TileRangeIntersectionType.B_CONTAINS_2_A_1) {
            throw new IllegalArgumentException("Invalid intersection type for split2ZonesTileRange: " + intersectionType);
        }

        List<TileRange> resultTileRanges = new java.util.ArrayList<>();
        resultTileRanges.add(tileRangeBig);

        if (tileRangeSmall.getMinTileX() < tileRangeBig.getMinTileX()) {

            int bigMinX = tileRangeBig.getMinTileX();
            int leftX = bigMinX - 1;
            int rightX = bigMinX;
            List<TileRange> splitTileRanges = tileRangeSmall.splitTileRangeByX(leftX, rightX);

            for (TileRange splitTileRange : splitTileRanges) {
                if (!splitTileRange.intersects(tileRangeBig)) {
                    resultTileRanges.add(splitTileRange);
                }
            }

        } else if (tileRangeSmall.getMaxTileX() > tileRangeBig.getMaxTileX()) {

            int bigMaxX = tileRangeBig.getMaxTileX();
            int leftX = bigMaxX;
            int rightX = bigMaxX + 1;
            List<TileRange> splitTileRanges = tileRangeSmall.splitTileRangeByX(leftX, rightX);

            for (TileRange splitTileRange : splitTileRanges) {
                if (!splitTileRange.intersects(tileRangeBig)) {
                    resultTileRanges.add(splitTileRange);
                }
            }

        } else if (tileRangeSmall.getMinTileY() < tileRangeBig.getMinTileY()) {

            int bigMinY = tileRangeBig.getMinTileY();
            int bottomY = bigMinY - 1;
            int topY = bigMinY;
            List<TileRange> splitTileRanges = tileRangeSmall.splitTileRangeByY(bottomY, topY);

            for (TileRange splitTileRange : splitTileRanges) {
                if (!splitTileRange.intersects(tileRangeBig)) {
                    resultTileRanges.add(splitTileRange);
                }
            }

        } else if (tileRangeSmall.getMaxTileY() > tileRangeBig.getMaxTileY()) {

            int bigMaxY = tileRangeBig.getMaxTileY();
            int bottomY = bigMaxY;
            int topY = bigMaxY + 1;
            List<TileRange> splitTileRanges = tileRangeSmall.splitTileRangeByY(bottomY, topY);

            for (TileRange splitTileRange : splitTileRanges) {
                if (!splitTileRange.intersects(tileRangeBig)) {
                    resultTileRanges.add(splitTileRange);
                }
            }
        }

        return resultTileRanges;
    }
}
