/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.raster;

public record TerrainRasterMetadata(
        int width,
        int height,
        int originalWidth,
        int originalHeight,
        double minLongitude,
        double minLatitude,
        double maxLongitude,
        double maxLatitude,
        float noDataValue) {

    public long payloadSizeBytes() {
        return (long) width * height * Float.BYTES;
    }
}
