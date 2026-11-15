/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.geotiff;

public enum TileRangeIntersectionType {

    UNKNOWN,
    NO_INTERSECTION,
    PARTIAL_OVERLAP,
    A_CONTAINS_B,
    B_CONTAINS_A,
    A_CONTAINS_2_B_0,
    A_CONTAINS_2_B_1,
    B_CONTAINS_2_A_0,
    B_CONTAINS_2_A_1,
    A_CONTAINS_1_B_1,
    HORIZONTAL_FULL_TOUCHING,
    VERTICAL_FULL_TOUCHING

}
