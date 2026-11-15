/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.basic.geometry.entities;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.joml.Vector3d;

@Slf4j
@Getter
@Setter

public class GaiaSegment {
    private Vector3d startPoint = null;
    private Vector3d endPoint = null;

    public GaiaSegment(Vector3d startPoint, Vector3d endPoint) {
        this.startPoint = startPoint;
        this.endPoint = endPoint;
    }

    public Vector3d getVector() {
        return new Vector3d(endPoint).sub(startPoint);
    }

    public double angRadToVector(Vector3d vector) {

        Vector3d segment = new Vector3d(endPoint).sub(startPoint);
        return segment.angle(vector);
    }

    public double angRadToSegment(GaiaSegment segment) {

        Vector3d segment1 = new Vector3d(endPoint).sub(startPoint);
        Vector3d segment2 = new Vector3d(segment.endPoint).sub(segment.startPoint);
        return segment1.angle(segment2);
    }

    public boolean check() {
        return startPoint != null && endPoint != null;
    }
}
