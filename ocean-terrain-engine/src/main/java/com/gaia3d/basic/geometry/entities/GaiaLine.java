/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.basic.geometry.entities;

import org.joml.Vector3d;

public class GaiaLine {
    private Vector3d point = null;
    private Vector3d direction = null;

    public GaiaLine(Vector3d point, Vector3d direction) {
        this.point = point;
        this.direction = direction;

        this.direction.normalize();
    }

    public Vector3d projectPoint(Vector3d p, Vector3d result) {
        double vx = p.x - point.x;
        double vy = p.y - point.y;
        double vz = p.z - point.z;

        double t = vx * direction.x + vy * direction.y + vz * direction.z;

        result.set(
                point.x + direction.x * t,
                point.y + direction.y * t,
                point.z + direction.z * t
        );

        return result;
    }

    public double distanceToPoint(Vector3d p) {

        double vx = p.x - point.x;
        double vy = p.y - point.y;
        double vz = p.z - point.z;

        double cx = vy * direction.z - vz * direction.y;
        double cy = vz * direction.x - vx * direction.z;
        double cz = vx * direction.y - vy * direction.x;

        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    public boolean intersectsPoint(Vector3d point, double error) {
        return distanceToPoint(point) <= error;
    }
}
