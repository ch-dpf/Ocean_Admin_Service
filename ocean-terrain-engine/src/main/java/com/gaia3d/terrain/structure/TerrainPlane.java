/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.structure;

import lombok.NoArgsConstructor;
import org.joml.Vector3d;

@NoArgsConstructor
public class TerrainPlane {

    private double a = 0;
    private double b = 0;
    private double c = 0;
    private double d = 0;

    public TerrainPlane(Vector3d p0, Vector3d p1, Vector3d p2) {
        this.set3Points(p0, p1, p2);
    }

    public void set3Points(Vector3d p0, Vector3d p1, Vector3d p2) {
        Vector3d v1 = new Vector3d(p1).sub(p0);
        Vector3d v2 = new Vector3d(p2).sub(p0);
        Vector3d normal = new Vector3d(v1).cross(v2).normalize();
        a = normal.x;
        b = normal.y;
        c = normal.z;
        d = -((a * p0.x) + (b * p0.y) + (c * p0.z));
    }

    public double getValueZ(double x, double y) {
        return -(a * x + b * y + d) / c;
    }

    public double evaluatePoint(double x, double y, double z) {

        return a * x + b * y + c * z + d;
    }
}
