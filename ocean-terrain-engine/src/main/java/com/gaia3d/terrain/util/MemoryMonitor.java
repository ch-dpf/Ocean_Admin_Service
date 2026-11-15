/*
 * SPDX-License-Identifier: MPL-2.0
 * 基于 Gaia3D/mago-3d-terrainer v1.14.2-release，已为本库同 JVM 执行改造。
 */
package com.gaia3d.terrain.util;

import com.gaia3d.util.DecimalUtils;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class MemoryMonitor {

    private static final double CRITICAL_MEMORY_THRESHOLD = 0.10;
    private static final double WARNING_MEMORY_THRESHOLD = 0.20;

    public static MemoryState checkMemory(String context) {
        MemoryState state = new MemoryState();

        if (state.isCritical) {
            log.error("[{}] CRITICAL memory pressure: {}% free ({} / {}). " +
                            "Recommend stopping to prevent OutOfMemoryError.",
                    context, state.getFormattedPercent(),
                    state.getAvailableMemoryDisplay(), state.getMaxMemoryDisplay());
        } else if (state.isWarning) {
            log.warn("[{}] LOW memory warning: {}% free ({} / {}). Approaching limits.",
                    context, state.getFormattedPercent(),
                    state.getAvailableMemoryDisplay(), state.getMaxMemoryDisplay());
        }

        return state;
    }

    public static MemoryGrowth calculateGrowth(long previousUsed, long currentUsed,
                                               int previousTriangles, int currentTriangles) {
        return new MemoryGrowth(previousUsed, currentUsed, previousTriangles, currentTriangles);
    }

    public static class MemoryState {
        public final long maxMemory;
        public final long totalMemory;
        public final long freeMemory;
        public final long usedMemory;
        public final double freeMemoryPercent;
        public final boolean isCritical;
        public final boolean isWarning;

        public MemoryState() {
            Runtime runtime = Runtime.getRuntime();
            this.maxMemory = runtime.maxMemory();
            this.totalMemory = runtime.totalMemory();
            this.freeMemory = runtime.freeMemory();
            this.usedMemory = totalMemory - freeMemory;

            long availableMemory = maxMemory - usedMemory;
            this.freeMemoryPercent = (double) availableMemory / maxMemory;

            this.isCritical = freeMemoryPercent < CRITICAL_MEMORY_THRESHOLD;
            this.isWarning = freeMemoryPercent < WARNING_MEMORY_THRESHOLD && !isCritical;
        }

        public String getMaxMemoryDisplay() {
            return DecimalUtils.byteCountToDisplaySize(maxMemory);
        }

        public String getTotalMemoryDisplay() {
            return DecimalUtils.byteCountToDisplaySize(totalMemory);
        }

        public String getFreeMemoryDisplay() {
            return DecimalUtils.byteCountToDisplaySize(freeMemory);
        }

        public String getUsedMemoryDisplay() {
            return DecimalUtils.byteCountToDisplaySize(usedMemory);
        }

        public String getAvailableMemoryDisplay() {
            long availableMemory = maxMemory - usedMemory;
            return DecimalUtils.byteCountToDisplaySize(availableMemory);
        }

        public String getFormattedPercent() {
            return String.format("%.1f", freeMemoryPercent * 100.0);
        }
    }

    public static class MemoryMetrics {
        public final long bytesPerTriangle;
        public final long bytesPerVertex;
        public final String formattedBytesPerTriangle;
        public final String formattedBytesPerVertex;

        public MemoryMetrics(long usedMemory, int triangleCount, int vertexCount) {
            this.bytesPerTriangle = triangleCount > 0 ? usedMemory / triangleCount : 0;
            this.bytesPerVertex = vertexCount > 0 ? usedMemory / vertexCount : 0;
            this.formattedBytesPerTriangle = bytesPerTriangle + " bytes";
            this.formattedBytesPerVertex = bytesPerVertex + " bytes";
        }
    }

    public static class MemoryGrowth {
        public final long memoryDelta;
        public final int triangleDelta;
        public final double growthRateMB;
        public final String formattedDelta;

        public MemoryGrowth(long previousUsed, long currentUsed,
                            int previousTriangles, int currentTriangles) {
            this.memoryDelta = currentUsed - previousUsed;
            this.triangleDelta = currentTriangles - previousTriangles;
            this.growthRateMB = memoryDelta / (1024.0 * 1024.0);
            this.formattedDelta = DecimalUtils.byteCountToDisplaySize(memoryDelta);
        }
    }
}
