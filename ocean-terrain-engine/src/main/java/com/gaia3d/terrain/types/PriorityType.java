/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PriorityType {
    RESOLUTION("resolution");

    private final String argumentName;

    public static PriorityType fromString(String text) {
        for (PriorityType type : PriorityType.values()) {
            if (type.argumentName.equalsIgnoreCase(text)) {
                return type;
            }
        }
        return RESOLUTION;
    }
}
