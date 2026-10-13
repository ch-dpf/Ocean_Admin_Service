package org.ocean.admin.gis.processing;

import java.nio.file.Path;

/** 单次文件处理的受控工作目录。 */
public record GisProcessingWorkspace(
        String outputKey,
        Path outputPath,
        Path tempPath,
        Path logPath) {
}
