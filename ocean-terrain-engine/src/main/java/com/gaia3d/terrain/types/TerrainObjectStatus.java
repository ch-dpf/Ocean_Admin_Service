/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TerrainObjectStatus {
    UNKNOWN(-1),
    ACTIVE(0),
    DELETED(1);

    private final int value;

    public static TerrainObjectStatus fromValue(int value) {
        for (TerrainObjectStatus type : TerrainObjectStatus.values()) {
            if (type.value == value) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
