/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.imagen.Interpolation;

@Getter
@RequiredArgsConstructor
public enum InterpolationType {

    NEAREST(Interpolation.INTERP_NEAREST, "nearest"),
    BILINEAR(Interpolation.INTERP_BILINEAR, "bilinear");

    private final int interpolation;
    private final String interpolationArgument;

    public static InterpolationType fromString(String interpolationArgument) {
        for (InterpolationType interpolationType : values()) {
            if (interpolationType.interpolationArgument.equals(interpolationArgument)) {
                return interpolationType;
            }
        }
        throw new IllegalArgumentException("Unknown interpolation type: " + interpolationArgument);
    }
}
