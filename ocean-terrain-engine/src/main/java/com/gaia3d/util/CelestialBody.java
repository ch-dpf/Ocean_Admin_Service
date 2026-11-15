/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.util;

import lombok.Getter;

@Getter
public enum CelestialBody {
    EARTH(
            "Earth",
            6378137.0,
            6356752.3142,
            6.69437999014E-3,
            "EPSG:4326"
    ),
    MOON(
            "Moon",
            1737400.0,
            1737400.0,
            0.0,
            "IAU:30100"
    );

    private final String displayName;
    private final double equatorialRadius;
    private final double polarRadius;
    private final double firstEccentricitySquared;
    private final String crsCode;

    private final double equatorialRadiusSquared;
    private final double polarRadiusSquared;

    CelestialBody(String displayName, double equatorialRadius, double polarRadius,
                  double firstEccentricitySquared, String crsCode) {
        this.displayName = displayName;
        this.equatorialRadius = equatorialRadius;
        this.polarRadius = polarRadius;
        this.firstEccentricitySquared = firstEccentricitySquared;
        this.crsCode = crsCode;
        this.equatorialRadiusSquared = equatorialRadius * equatorialRadius;
        this.polarRadiusSquared = polarRadius * polarRadius;
    }

    public static CelestialBody fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Body value cannot be null");
        }
        return switch (value.trim().toLowerCase()) {
            case "earth" -> EARTH;
            case "moon" -> MOON;
            default -> throw new IllegalArgumentException("Unknown celestial body: " + value);
        };
    }

    public boolean isSphere() {
        return firstEccentricitySquared == 0.0;
    }
}
