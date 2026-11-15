/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.tile.geotiff;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.geotools.coverage.grid.GridCoverage2D;

@Getter
@Setter
@AllArgsConstructor
public class RasterInfo {
    private String name;
    private GridCoverage2D gridCoverage2D;
}
