package org.ocean.admin.gis.terrain.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerrainOptionsTest {

    @Test
    void rejectsDepthOutsideSupportedRange() {
        assertThatThrownBy(() -> new TerrainOptions(
                0, 23, "Ellipsoid", null, null, null, null,
                true, false, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    @Test
    void rejectsMinDepthGreaterThanMaxDepth() {
        assertThatThrownBy(() -> new TerrainOptions(
                10, 9, "Ellipsoid", null, null, null, null,
                true, false, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minDepth");
    }
}
