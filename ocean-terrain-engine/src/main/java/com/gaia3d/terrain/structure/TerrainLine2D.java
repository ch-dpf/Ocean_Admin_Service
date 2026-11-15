/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.structure;

import lombok.NoArgsConstructor;

import static java.lang.Math.abs;

@NoArgsConstructor
public class TerrainLine2D {
    private double posX;
    private double posY;
    private double dirX;
    private double dirY;

    public void setBy2Points(double startPosX, double startPosY, double endPosX, double endPosY) {
        this.posX = startPosX;
        this.posY = startPosY;
        double xDiff = endPosX - startPosX;
        double yDiff = endPosY - startPosY;
        double length = Math.sqrt(xDiff * xDiff + yDiff * yDiff);

        this.dirX = (endPosX - startPosX) / length;
        this.dirY = (endPosY - startPosY) / length;
    }

    public byte relativePositionOfPoint(double px, double py, double error) {

        double x = px - this.posX;
        double y = py - this.posY;
        double length = Math.sqrt(x * x + y * y);
        x = x / length;
        y = y / length;

        double cross = this.dirX * y - this.dirY * x;
        if (abs(cross) < error) {
            return (byte) 0;
        } else if (cross > 0) {
            return (byte) 1;
        } else {
            return (byte) 2;
        }
    }
}
