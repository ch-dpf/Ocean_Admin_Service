package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.dto.GisProcessingParameters;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** 将多个文件或目录输入处理为统一瓦片集的引擎契约。 */
public interface GisFileProcessingEngine {

    GisProcessingType type();

    void validate(String originalName, String extension, Path inputPath);

    /** 使用任务参数处理多个输入文件并生成一个统一瓦片集。 */
    void process(List<Path> inputPaths, GisProcessingWorkspace workspace,
            GisProcessingParameters parameters, Consumer<ProgressStage> progressListener);

    /** 使用任务参数处理目录输入。 */
    default void processFolder(Path inputFolder, GisProcessingWorkspace workspace,
            GisProcessingParameters parameters,
            Consumer<ProgressStage> progressListener) {
        throw new UnsupportedOperationException(type().displayName() + "引擎不支持目录输入");
    }
}
