package org.ocean.admin.gis.terrain.engine.mago;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** 同 JVM 地形引擎的任务准入与缓存预算。 */
@ConfigurationProperties(prefix = "gis.terrain.mago")
public class MagoTerrainProperties {
    private boolean enabled = true;
    private Duration taskTimeout = Duration.ofHours(72);
    private int maxConcurrentTasks = 1;
    private long rasterCacheBytes = 256L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getTaskTimeout() { return taskTimeout; }
    public void setTaskTimeout(Duration taskTimeout) { this.taskTimeout = taskTimeout; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int maxConcurrentTasks) { this.maxConcurrentTasks = maxConcurrentTasks; }
    public long getRasterCacheBytes() { return rasterCacheBytes; }
    public void setRasterCacheBytes(long rasterCacheBytes) { this.rasterCacheBytes = rasterCacheBytes; }
}
