/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.util;

import com.gaia3d.basic.halfedge.PlaneType;
import org.joml.Vector3d;

/** 地形几何计算所需的向量与投影工具。 */
public final class GeometryUtils {
    private GeometryUtils() {}

    public static double cosineBetweenUnitaryVectors(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }

    public static PlaneType getBestPlaneToProject(Vector3d normal) {

        float absX = Math.abs((float) normal.x);
        float absY = Math.abs((float) normal.y);
        float absZ = Math.abs((float) normal.z);

        if (absX >= absY && absX >= absZ) {
            return normal.x >= 0 ? PlaneType.YZ : PlaneType.YZNEG;
        } else if (absY >= absX && absY >= absZ) {
            return normal.y >= 0 ? PlaneType.XZ : PlaneType.XZNEG;
        } else {
            return normal.z >= 0 ? PlaneType.XY : PlaneType.XYNEG;
        }
    }
}
