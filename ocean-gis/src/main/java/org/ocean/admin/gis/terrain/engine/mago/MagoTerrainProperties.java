package org.ocean.admin.gis.terrain.engine.mago;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** mago-3d-terrainer 运行配置。 */
@ConfigurationProperties(prefix = "gis.terrain.mago")
public class MagoTerrainProperties {

    private boolean enabled = true;
    private String javaCommand = "java";
    private Path jarPath = Path.of("tools/mago/mago-3d-terrainer-1.14.2-release.jar");
    private String maxHeap = "8g";
    private Duration processTimeout = Duration.ofHours(24);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJavaCommand() {
        return javaCommand;
    }

    public void setJavaCommand(String javaCommand) {
        this.javaCommand = javaCommand;
    }

    public Path getJarPath() {
        return jarPath;
    }

    public void setJarPath(Path jarPath) {
        this.jarPath = jarPath;
    }

    public String getMaxHeap() {
        return maxHeap;
    }

    public void setMaxHeap(String maxHeap) {
        this.maxHeap = maxHeap;
    }

    public Duration getProcessTimeout() {
        return processTimeout;
    }

    public void setProcessTimeout(Duration processTimeout) {
        this.processTimeout = processTimeout;
    }
}
