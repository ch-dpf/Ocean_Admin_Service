/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.util;

import lombok.experimental.UtilityClass;
import org.joml.Vector2f;
import org.joml.Vector3f;

@UtilityClass
public class OctNormalCalculator {

    public static Vector2f toShortNormal(Vector2f value) {
        float x = (float) Math.round((clamp(value.x, -1.0f, 1.0f) * 0.5f + 0.5f) * 255.0f);
        float y = (float) Math.round((clamp(value.y, -1.0f, 1.0f) * 0.5f + 0.5f) * 255.0f);
        return new Vector2f(x, y);
    }

    public static Vector2f signNotZero(Vector2f value) {
        return new Vector2f(value.x >= 0 ? 1 : -1, value.y >= 0 ? 1 : -1);
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Vector2f encodeOctNormal(Vector3f normal) {
        float x = normal.x;
        float y = normal.y;
        float z = normal.z;

        float den = Math.abs(x) + Math.abs(y) + Math.abs(z);
        float u = x / den;
        float v = y / den;
        Vector2f p = new Vector2f(u, v);

        if (z <= 0) {
            u = (float) ((1.0 - Math.abs(p.y)) * signNotZero(p).x);
            v = (float) ((1.0 - Math.abs(p.x)) * signNotZero(p).y);
            p = new Vector2f(u, v);
        }
        p = toShortNormal(p);
        return p;
    }

    public static byte[] encodeOctNormalByte(Vector3f normal) {
        Vector2f octNormal = encodeOctNormal(normal);
        return new byte[]{(byte) octNormal.x, (byte) octNormal.y};
    }
}
