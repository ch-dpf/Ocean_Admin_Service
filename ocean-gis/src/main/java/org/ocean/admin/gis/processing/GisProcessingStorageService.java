package org.ocean.admin.gis.processing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Locale;

/** 为切片任务分配受控且可持久化引用的输出路径。 */
@Service
public class GisProcessingStorageService {

    private final Path processingRoot;

    public GisProcessingStorageService(
            @Value("${gis.processing.base-path:processed/gis}") String basePath) {
        this.processingRoot = Path.of(basePath).toAbsolutePath().normalize();
    }

    public GisProcessingWorkspace workspace(
            GisProcessingType type, Long fileMetaId, String taskNo) {
        if (type == null || fileMetaId == null || taskNo == null
                || !taskNo.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("无法创建非法的 GIS 处理工作目录");
        }
        String outputKey = type.name().toLowerCase(Locale.ROOT)
                + "/" + fileMetaId + "/" + taskNo;
        Path taskRoot = resolve(outputKey);
        return new GisProcessingWorkspace(
                outputKey,
                taskRoot.resolve("tiles"),
                taskRoot.resolve("temp"),
                taskRoot.resolve("logs").resolve("engine.log"));
    }

    private Path resolve(String key) {
        Path resolved = processingRoot
                .resolve(key.replace('/', java.io.File.separatorChar))
                .normalize();
        if (!resolved.startsWith(processingRoot)) {
            throw new IllegalArgumentException("非法处理产物 Key: " + key);
        }
        return resolved;
    }
}
