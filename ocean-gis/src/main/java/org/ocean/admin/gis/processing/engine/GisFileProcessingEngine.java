package org.ocean.admin.gis.processing.engine;

import org.ocean.admin.gis.processing.GisProcessingProgress;
import org.ocean.admin.gis.processing.GisProcessingWorkspace;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.ocean.admin.gis.dto.GisProcessingParameters;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** 单文件切片引擎适配器。 */
public interface GisFileProcessingEngine {

    GisProcessingType type();

    void validate(String originalName, String extension, Path inputPath);

    /** 处理多个输入文件并生成一个统一瓦片集。 */
    default void process(List<Path> inputPaths, GisProcessingWorkspace workspace,
            Consumer<GisProcessingProgress> progressListener) {
        throw new UnsupportedOperationException(type().displayName() + "引擎不支持文件列表处理");
    }

    /** 使用任务参数处理多个输入文件。 */
    default void process(List<Path> inputPaths, GisProcessingWorkspace workspace,
            GisProcessingParameters parameters, Consumer<GisProcessingProgress> progressListener) {
        process(inputPaths, workspace, progressListener);
    }

    /** 处理无需文件元数据、直接来自服务器目录的输入。 */
    default void processFolder(
            Path inputFolder,
            GisProcessingWorkspace workspace,
            Consumer<GisProcessingProgress> progressListener) {
        throw new UnsupportedOperationException(type().displayName() + "引擎不支持目录输入");
    }
}
